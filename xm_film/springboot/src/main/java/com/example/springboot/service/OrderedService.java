package com.example.springboot.service;

import com.example.springboot.common.BaseMapper;
import com.example.springboot.common.BatchDeleteGuard;
import com.example.springboot.common.OrderNumber;
import com.example.springboot.common.OrderStatusCriteria;
import com.example.springboot.common.PriceParser;
import com.example.springboot.common.SeatParser;
import com.example.springboot.common.SecureCodes;
import com.example.springboot.common.SystemClock;
import com.example.springboot.dto.response.TodayRevenueView;
import com.example.springboot.common.BaseService;
import com.example.springboot.common.enums.ErrorCode;
import com.example.springboot.common.enums.OrderStatus;
import com.example.springboot.common.enums.PayResult;
import com.example.springboot.common.enums.RecordStatus;
import com.example.springboot.common.enums.RoleEnum;
import com.example.springboot.dto.response.SeatOccupancyView;
import com.example.springboot.dto.response.TicketVoucherView;
import com.example.springboot.entity.Cinema;
import com.example.springboot.entity.Film;
import com.example.springboot.entity.Ordered;
import com.example.springboot.entity.OutboxEvent;
import com.example.springboot.entity.Record;
import com.example.springboot.entity.Room;
import com.example.springboot.entity.ScreeningSeat;
import com.example.springboot.common.OrderRateLimiter;
import com.example.springboot.exception.CustomException;
import com.example.springboot.mapper.CinemaMapper;
import com.example.springboot.mapper.FilmMapper;
import com.example.springboot.mapper.OrderedMapper;
import com.example.springboot.mapper.RecordMapper;
import com.example.springboot.mapper.RoomMapper;
import com.example.springboot.mapper.ScreeningSeatMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 订单状态机与座位占用的唯一出处：选座建单（锁座、超时废单）、支付、取消、退票、
 * 取票（柜台核销与凭码自助）全部经此流转，订单号与取票码也在这里生成。
 */
@Service
@Transactional(readOnly = true)
public class OrderedService extends BaseService<Ordered> {

    // 座位号形状与上限统一在 SeatParser —— 所有"什么算合法座位"都在那里

    /**
     * 传给 mapper 的状态取值。XML 里不再写中文状态字面量，见 {@link OrderStatusCriteria}。
     */
    private static final OrderStatusCriteria STATUS = OrderStatusCriteria.INSTANCE;

    /** 退票截止：放映前 60 分钟（与前台词：未取票用户在放映前60分钟可退票） */
    private static final int REFUND_DEADLINE_MINUTES = 60;

    /**
     * 锁座窗口：待支付订单的存活分钟数（BL-05）。
     *
     * <p>实际写入由 SQL 完成（{@code OrderedMapper.insert} 的 {@code NOW() + INTERVAL 5 MINUTE}），
     * 因为超时只在数据库时钟下比较。本常量与 mapper 中的数值必须一致，
     * 由 {@code PendingTimeoutClockTest} 断言校验，改一处漏一处会立即红。
     */
    public static final int PENDING_TIMEOUT_MINUTES = 5;

    /** 取票码随机段长度（分组显示，不含分隔符） */
    private static final int PICKUP_CODE_LENGTH = 8;

    /** 取票码分组分隔符，仅用于可读性；存储与展示用同一形态，核销前做归一 */
    private static final char PICKUP_CODE_GROUP_SEPARATOR = '-';

    /** 取票码生成字母表：剔除 I/L/O/0/1，人工抄写到自助机上最容易看错的几个 */
    private static final String PICKUP_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /**
     * 只有终态废单可以清理。已成交订单必须走退票流程，
     * 否则「删除订单」会成为绕过退票与资金凭证的后门。
     */
    private static final Set<String> DELETABLE_STATUSES =
            Set.of(OrderStatus.CANCELLED, OrderStatus.REFUNDED);

    private final OrderedMapper orderedMapper;

    private final RecordMapper recordMapper;

    private final FilmMapper filmMapper;

    private final RoomMapper roomMapper;

    private final CinemaMapper cinemaMapper;

    private final ScreeningSeatMapper screeningSeatMapper;

    private final WalletService walletService;

    private final PayPasswordService payPasswordService;

    /** 下单限流（可空：单测直接 new 时不启用，避免为限流引入 Redis 依赖） */
    private OrderRateLimiter orderRateLimiter;

    @Autowired(required = false)
    public void setOrderRateLimiter(OrderRateLimiter orderRateLimiter) {
        this.orderRateLimiter = orderRateLimiter;
    }

    /** Outbox 事件服务（可空：同上，保持单元测试无需容器） */
    private OutboxService outboxService;

    @Autowired(required = false)
    public void setOutboxService(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    /** 幂等查询（可空：同上）；并发重复提交时用独立事务读已提交的原订单 */
    private OrderIdempotencyService idempotencyService;

    @Autowired(required = false)
    public void setIdempotencyService(OrderIdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    public OrderedService(OrderedMapper orderedMapper, RecordMapper recordMapper, FilmMapper filmMapper,
                          RoomMapper roomMapper, CinemaMapper cinemaMapper,
                          ScreeningSeatMapper screeningSeatMapper,
                          WalletService walletService, PayPasswordService payPasswordService) {
        this.orderedMapper = orderedMapper;
        this.recordMapper = recordMapper;
        this.filmMapper = filmMapper;
        this.roomMapper = roomMapper;
        this.cinemaMapper = cinemaMapper;
        this.screeningSeatMapper = screeningSeatMapper;
        this.walletService = walletService;
        this.payPasswordService = payPasswordService;
    }

    @Override
    protected BaseMapper<Ordered> mapper() {
        return orderedMapper;
    }

    public void applyScope(Ordered ordered, String role, Integer userId) {
        if (ordered == null || role == null || userId == null) {
            return;
        }
        if (RoleEnum.USER.name().equals(role)) {
            ordered.setUserId(userId);
        } else if (RoleEnum.CINEMA.name().equals(role)) {
            ordered.setCinemaId(userId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Ordered ordered) {
        insertOrder(ordered, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void createOrder(Ordered ordered, String role, Integer tokenUserId) {
        insertOrder(ordered, role, tokenUserId);
    }

    private void insertOrder(Ordered ordered, String role, Integer tokenUserId) {
        // ===== 限流闸：单用户每分钟最多 20 次下单尝试，挡住脚本刷单与前端异常重试风暴 =====
        // 放在最前：连数据库都不必碰就能拒掉超额请求。Redis 故障时该检查自动放行。
        if (orderRateLimiter != null && !orderRateLimiter.tryAcquire(tokenUserId)) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "操作过于频繁，请稍后再试");
        }
        requireCreatable(ordered, role, tokenUserId);

        // ===== 幂等第一道闸：请求重用同一个 request_id 时，直接视为成功，不再重复建单 =====
        // 场景：用户点"提交订单"后网络超时、前端自动重试，第二次请求已带同一 request_id。
        // 若不做这一步，第二次会再锁一次座位、再插一条订单 —— 同一笔业务扣两遍。
        if (ordered.getRequestId() != null && !ordered.getRequestId().isBlank()) {
            Ordered existing = findExistingOrder(ordered.getUserId(), ordered.getRequestId());
            if (existing != null) {
                // 同一标识必须对应同一份下单内容，否则说明标识被复用/伪造，不能把别的订单返回给用户
                ensureSameRequestPayload(existing, ordered);
                // 把已存在的订单回填到入参对象：调用方（Controller）用它拼响应，
                // 重复提交拿到的必须是"原来那一单"，而不是一个空壳。
                BeanUtils.copyProperties(existing, ordered);
                return;
            }
        }

        Record recordItem = validatePurchasableRecord(ordered.getRecordId());
        List<String> seats = normalizeSeats(ordered.getSeat(), recordItem.getRoomId());
        // ===== 核心改造：用 screening_seat 表的条件更新实现座位级锁 =====
        // 替代原 ensureSeatsFree（内存扫全场次所有活跃订单判重）+ 场次行锁 selectByIdForUpdate
        // 单条 UPDATE ... WHERE seat_code IN (...) AND status = 0 原子地把"可用"座位锁给本单。
        // 必须校验受影响行数 == 座位总数：3 个座位只有 1 个可用时，若只看"是否 > 0"
        // 会带着 2 个没锁上的座位去建单 —— 这就是部分成功导致的超卖。
        // 不相等即抛异常，事务回滚，刚锁上的那几个座位随之恢复可用。
        String orderNo = generateOrderNo();
        int locked = screeningSeatMapper.batchLockSeats(
                recordItem.getId(), seats, orderNo, PENDING_TIMEOUT_MINUTES);
        if (locked != seats.size()) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT,
                    "所选座位中部分或全部已被占用，请刷新选座图重试");
        }
        fillOrderSnapshot(ordered, recordItem, seats);
        ordered.setOrders(orderNo);
        try {
            orderedMapper.insert(ordered);
        } catch (DuplicateKeyException e) {
            // ===== 幂等第二道闸：并发重复提交时，唯一索引 UNIQUE(user_id, request_id) 兜底 =====
            // 两个请求同时通过上面的预查（都还没插），只有一个插入成功。
            // 落败方先释放本事务刚锁的座位（否则这些座位会一直锁到超时补偿），
            // 再用独立事务读出对手提交的原订单返回 —— 客户端拿到的是"同一笔订单"而不是 409。
            // 必须按 record_id + 座位集合 + lock_order_no 释放：此刻订单行并未插入成功，
            // 任何依赖 ordered 表 JOIN 的释放语句都匹配不到任何行。
            screeningSeatMapper.batchReleaseSeats(recordItem.getId(), seats, orderNo);
            Ordered existing = findExistingOrder(ordered.getUserId(), ordered.getRequestId());
            if (existing == null) {
                throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "订单已提交，请勿重复下单");
            }
            ensureSameRequestPayload(existing, ordered);
            BeanUtils.copyProperties(existing, ordered);
        }
    }

    /** 独立事务优先（能看到并发对手刚提交的订单），组件缺失时回退当前事务查询 */
    private Ordered findExistingOrder(Integer userId, String requestId) {
        if (idempotencyService != null) {
            return idempotencyService.findByUserAndRequestId(userId, requestId);
        }
        return orderedMapper.selectByUserAndRequestId(userId, requestId);
    }

    /**
     * 同一 requestId 只能对应同一份下单内容：场次一致、座位集合一致。
     * 复用标识却换了内容（换了场次/座位）时拒绝，避免把上一次的订单当作本次结果返回。
     */
    private static void ensureSameRequestPayload(Ordered existing, Ordered current) {
        boolean sameRecord = existing.getRecordId() != null
                && existing.getRecordId().equals(current.getRecordId());
        Set<String> existingSeats = new TreeSet<>();
        splitSeats(existing.getSeat()).forEach(seat -> existingSeats.add(seat.trim()));
        Set<String> currentSeats = new TreeSet<>();
        splitSeats(current.getSeat()).forEach(seat -> currentSeats.add(seat.trim()));
        if (!sameRecord || !existingSeats.equals(currentSeats)) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT,
                    "同一请求标识提交了不同订单内容，请刷新页面重试");
        }
    }

    /**
     * 校验场次可售性，并对场次行加共享锁（FOR SHARE）。
     *
     * <p>共享锁允许多个下单事务并发持有，但会阻塞 {@code RecordService.updateScoped}
     * 对场次行的排他锁（FOR UPDATE），反之亦然。这样"改影厅→删旧座位→重建"
     * 与"下单→锁旧座位→建单"就不会交叉执行：
     * <ul>
     *   <li>改影厅先拿到排他锁：下单等待，改完后下单锁的是新厅座位；</li>
     *   <li>下单先拿到共享锁：改影厅等待，下单提交后改影厅读到已占用并拒绝。</li>
     * </ul>
     * 座位占用本身仍由 {@code screening_seat} 的条件更新承担。
     */
    private Record validatePurchasableRecord(Integer recordId) {
        Record recordItem = recordMapper.selectByIdForShare(recordId);
        if (recordItem == null) {
            throw new CustomException(ErrorCode.PARAM_INVALID, "放映场次不存在");
        }
        if (RecordStatus.STOPPED.equals(recordItem.getStatus())) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "该场次已停售");
        }
        if (!RecordService.isPurchasable(recordItem)) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "该场次已开场，无法购票");
        }
        return recordItem;
    }

    /** 建单前的入参/角色校验；通过后把 token 中的用户 ID 落到订单上 */
    private static void requireCreatable(Ordered ordered, String role, Integer tokenUserId) {
        if (role != null && !RoleEnum.USER.name().equals(role)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "仅普通用户可创建订单");
        }
        if (ordered == null || ordered.getRecordId() == null) {
            throw new CustomException(ErrorCode.PARAM_INVALID, "缺少放映场次");
        }
        if (tokenUserId != null) {
            ordered.setUserId(tokenUserId);
        }
        if (ordered.getUserId() == null) {
            throw new CustomException(ErrorCode.PARAM_INVALID, "缺少购票用户");
        }
    }

    /** 用场次快照填充订单：价格、座位、场次信息与图片（orderNo 已在 insertOrder 提前生成） */
    private void fillOrderSnapshot(Ordered ordered, Record recordItem, List<String> seats) {
        Film film = filmMapper.selectById(recordItem.getFilmId());
        BigDecimal price = parsePrice(recordItem.getPrice());
        BigDecimal total = price.multiply(BigDecimal.valueOf(seats.size())).setScale(2, RoundingMode.HALF_UP);

        ordered.setRecordId(recordItem.getId());
        ordered.setFilmId(recordItem.getFilmId());
        ordered.setCinemaId(recordItem.getCinemaId());
        ordered.setRoomId(recordItem.getRoomId());
        ordered.setAppointment("场次ID:" + recordItem.getId());
        ordered.setStart(recordItem.getStart());
        ordered.setNumber(seats.size());
        ordered.setTotal(total);
        ordered.setUnitPrice(price);
        ordered.setStatus(OrderStatus.PENDING_PAYMENT);
        ordered.setSeat(String.join(",", seats));
        ordered.setCreateTime(SystemClock.nowFormatted());
        ordered.setPendingTimeoutFromDb(true);
        if (film != null) {
            ordered.setImg(film.getImg());
        }
    }

    /**
     * 支付待支付订单。
     *
     * 超时分支不能抛异常返回：本方法标注了 rollbackFor = Exception.class，
     * 抛异常会把"把订单置为已取消"这次写入一起回滚，订单又变回待支付。
     * 因此超时改为返回 {@link PayResult#TIMEOUT_CANCELLED}，由控制器映射为业务错误。
     */
    @Transactional(rollbackFor = Exception.class)
    public PayResult payOrder(Integer id, String role, Integer userId, String payPassword) {
        Ordered ordered = orderedMapper.selectByIdForUpdate(id);
        if (ordered == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!OrderStatus.PENDING_PAYMENT.equals(ordered.getStatus())) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "当前状态不允许支付");
        }
        ensureOrderAccess(ordered, role, userId);

        if (isPendingTimedOut(ordered)) {
            Ordered cancel = new Ordered();
            cancel.setId(id);
            cancel.setStatus(OrderStatus.CANCELLED);
            cancel.setClearPendingTimeout(true);
            orderedMapper.updateById(cancel);
            // ===== 超时取消也要释放已锁定的座位 =====
            screeningSeatMapper.releaseByOrderNo(ordered.getOrders());
            return PayResult.TIMEOUT_CANCELLED;
        }

        // 锁排片行而不是普通读：与 addScoped / updateScoped（改时间、停售）和
        // deleteScoped（锁排片行后数订单，见 BL-02）抢同一把锁，避免
        // "刚判完可购、管理员同时把场次改到过去"的窗口。
        Record recordItem = recordMapper.selectByIdForUpdate(ordered.getRecordId());
        if (recordItem == null) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "放映场次已不存在，无法支付");
        }
        if (!RecordService.isPurchasable(recordItem)) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "该场次已开场或已停售，无法支付");
        }

        // 支付密码是这一笔资金操作的授权凭证，位置固定在扣款之前：验不过就一分钱不动。
        // 校验方法刻意"返回异常而不是抛异常"：失败计数得先由它自己的事务提交，本事务
        // 随后的回滚才带不走它（见 PayPasswordService.checkForPayment 的注释）。
        // 密码取的是**订单归属者**的 —— 扣谁的余额就验谁的密码，ADMIN / CINEMA 因此无法代付。
        CustomException passwordRejected = payPasswordService.checkForPayment(ordered.getUserId(), payPassword);
        if (passwordRejected != null) {
            throw passwordRejected;
        }

        // 余额校验与扣减必须与出票在同一事务内：扣款失败（余额不足）整笔回滚，
        // 订单停在待支付、pending_timeout_at 不变、座位继续锁定，用户充值后可继续支付。
        walletService.debitPurchase(ordered.getUserId(), orderAmount(ordered), id);

        // ===== 核心改造：支付成功后把座位从"已锁定"原子转为"已售出" =====
        // 关键带 lock_order_no 条件：防止订单 A 支付成功时，订单 B 已经锁定了同一座位
        // （旧事务误更新新状态）—— 只有锁定方自己才能把锁定转为售出。
        // 受影响行数必须等于订单座位数：锁座被超时任务提前释放（或锁已被他人接手）时，
        // 这里会出现差异，此单必须整体回滚，绝不能出现"扣了钱、座位却不是自己的"。
        int sold = screeningSeatMapper.markSoldByOrderNo(ordered.getOrders());
        if (sold != ordered.getNumber()) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT,
                    "座位锁定已失效（可能已超时释放），请刷新选座图后重新下单");
        }

        Ordered update = new Ordered();
        update.setId(id);
        update.setStatus(OrderStatus.PENDING);
        // 离开待支付：倒计时作废，显式清空（不能靠 pendingTimeoutAt=null，那会被守卫跳过）
        update.setClearPendingTimeout(true);
        update.setPayTime(now());
        update.setPayAmount(ordered.getTotal());
        // 取票码与扣款同一事务生成：不存在"扣了钱没码"或"有码没扣钱"的中间态，
        // 而未支付的订单也不会有码 —— 取票大厅因此天然拿不到没付款的单。
        update.setPickupCode(generatePickupCode());
        orderedMapper.updateById(update);

        // ===== Outbox：与支付同事务落一条 ORDER_PAID 事件 =====
        // 事务提交 → 事件必在；事务回滚 → 事件同退。后续由派发器异步投递，失败可重试。
        if (outboxService != null) {
            ordered.setStatus(OrderStatus.PENDING);
            outboxService.record(OutboxEvent.TYPE_ORDER_PAID, ordered);
        }
        return PayResult.PAID;
    }

    /**
     * 退票：仅待取票（即已支付未取票）订单可退，且需在放映前 60 分钟之前办理。
     * 退票后订单流转为已退票，座位不再计入占用（见 OrderedMapper.seatActiveCondition）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void refundOrder(Integer id, String role, Integer userId) {
        Ordered ordered = orderedMapper.selectByIdForUpdate(id);
        ensureOrderAccess(ordered, role, userId);
        if (!OrderStatus.PENDING.equals(ordered.getStatus())) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "仅待取票订单可退票");
        }

        LocalDateTime start = RecordService.readStart(ordered.getStart());
        if (start == null) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "订单缺失放映时间，无法退票，请联系影院");
        }
        if (LocalDateTime.now(ZoneId.systemDefault()).isAfter(start.minusMinutes(REFUND_DEADLINE_MINUTES))) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT,
                    "已过退票截止时间（放映前 " + REFUND_DEADLINE_MINUTES + " 分钟）");
        }

        // 退款入账与状态流转同事务：入账成功但状态没改回去的情况不会出现
        walletService.creditRefund(ordered.getUserId(), orderAmount(ordered), id);

        Ordered update = new Ordered();
        update.setId(id);
        update.setStatus(OrderStatus.REFUNDED);
        update.setRefundTime(now());
        update.setRefundAmount(ordered.getTotal());
        update.setClearPendingTimeout(true);
        orderedMapper.updateById(update);
        // ===== 退票时释放已售出座位，恢复为可用 =====
        screeningSeatMapper.releaseSoldByOrderNo(ordered.getOrders());
        if (outboxService != null) {
            ordered.setStatus(OrderStatus.REFUNDED);
            outboxService.record(OutboxEvent.TYPE_ORDER_REFUNDED, ordered);
        }
    }

    private boolean isPendingTimedOut(Ordered ordered) {
        // BL-05：pending_timeout_at 由数据库时钟写入（NOW() + INTERVAL），因此判定也必须用
        // 同一个时钟。用 JVM 时钟比较会在两者时区不一致时给出相反结论：
        // 清理任务（纯 SQL，用 DB NOW()）认为没过期，而支付路径认为已过期 ——
        // 用户付不了款，座位却仍占着，直到清理任务按自己的判断放出来。
        //
        // 这里改成与清理任务同一条 SQL 谓词判定的代价最直接：
        // 直接问库「这一行是否已超时」，JVM 时钟彻底不参与。
        return orderedMapper.countExpiredById(ordered.getId(), STATUS) > 0;
    }

    private String now() {
        return SystemClock.nowFormatted();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Ordered ordered) {
        // 需要库内行回填 pending_timeout_at，故就地读一次；存在性随之在此判定，
        // 不另调 super.update（那会重复一次 selectById）。口径与基类一致：查不到即 NOT_FOUND。
        Ordered db = mapper().selectById(ordered.getId());
        if (db == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "订单不存在，可能已被删除");
        }
        ordered.setPendingTimeoutAt(db.getPendingTimeoutAt());
        ordered.setStatus(null);
        mapper().updateById(ordered);
    }

    /**
     * 取票大厅自助核销：凭取票码出票。**不需要登录** —— 码本身就是授权凭证，
     * 谁持有码谁就能取这张票，这正是自助机的工作方式（匿名放行的说明见 TicketController）。
     *
     * 码不带任何有效/失效标记，可用性完全派生自订单状态：唯一接受的前提是
     * {@code status = '待取票'}。于是「一单一码」「用过即废」「退票/取消作废」
     * 「没付款不出发」四条都由这一个前提实现，不存在"新增状态时忘了同步"的空间。
     *
     * 与 {@link #pickupOrder} 的关系：两者是同一个状态迁移的两个入口 ——
     * pickupOrder 是影院柜台的员工操作（仅 CINEMA，见该方法的注释），本方法是自助机通路。
     */
    @Transactional(rollbackFor = Exception.class)
    public TicketVoucherView redeemByCode(String rawCode) {
        String code = normalizePickupCode(rawCode);
        if (code.isEmpty()) {
            throw new CustomException(ErrorCode.PARAM_INVALID, "取票码无效，请核对后重试");
        }

        Ordered ordered = orderedMapper.selectByPickupCode(code);
        if (ordered == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "取票码无效，请核对后重试");
        }
        // 四种情形分开报，不用一句"取票失败"糊过去 —— 用户需要知道下一步该做什么
        if (OrderStatus.PICKED_UP.equals(ordered.getStatus())) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "该票已取出，请勿重复取票");
        }
        if (OrderStatus.REFUNDED.equals(ordered.getStatus())) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "该订单已退票，取票码已失效");
        }
        if (OrderStatus.CANCELLED.equals(ordered.getStatus())) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "该订单已取消，取票码已失效");
        }
        if (OrderStatus.PENDING_PAYMENT.equals(ordered.getStatus())) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "该订单尚未支付，请先完成支付");
        }
        // 正面守卫：走到这里状态只可能是 待取票。这一句不是摆设 —— ordered.status 是没有
        // 约束的可空 VARCHAR，NULL 或脏值会绕过上面四条具体分支，一直落到下面的条件更新上
        // 拿到 0 行，被误报成"该票已取出"。
        if (!OrderStatus.PENDING.equals(ordered.getStatus())) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "当前订单状态不可取票，请联系影院");
        }

        Film film = ordered.getFilmId() == null ? null : filmMapper.selectById(ordered.getFilmId());
        if (isScreeningOver(ordered, film)) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "该场次已结束，取票码已失效");
        }

        if (orderedMapper.markPickedUpByCode(code, OrderStatus.PICKED_UP, OrderStatus.PENDING) == 0) {
            // 读状态到改库之间被人抢先核销了同一个码。不靠悲观锁，靠状态条件更新的行数判定。
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "该票已取出，请勿重复取票");
        }
        return buildVoucher(ordered, film);
    }

    /**
     * 取票码有效期到放映结束：放映开始 + 片长。片长缺失时与排片冲突检测同口径兜底。
     * 放映时间缺失时不判过期 —— 宁可放行让人工处理，也不把已付款的票锁死。
     */
    private boolean isScreeningOver(Ordered ordered, Film film) {
        LocalDateTime start = RecordService.readStart(ordered.getStart());
        if (start == null) {
            return false;
        }
        int minutes = film != null && film.getTime() != null && film.getTime() > 0
                ? film.getTime()
                : RecordService.DEFAULT_DURATION_MINUTES;
        return LocalDateTime.now(ZoneId.systemDefault()).isAfter(start.plusMinutes(minutes));
    }

    /** 组装出票凭条：只带展示字段，订单号/金额/userId 一概不出现在匿名响应里 */
    private TicketVoucherView buildVoucher(Ordered ordered, Film film) {
        Cinema cinema = ordered.getCinemaId() == null ? null : cinemaMapper.selectById(ordered.getCinemaId());
        Room room = ordered.getRoomId() == null ? null : roomMapper.selectById(ordered.getRoomId());

        TicketVoucherView voucher = new TicketVoucherView();
        voucher.setFilmTitle(film == null ? null : film.getTitle());
        voucher.setCinemaName(cinema == null ? null : cinema.getName());
        voucher.setRoomName(room == null ? null : room.getName());
        voucher.setStart(ordered.getStart());
        voucher.setSeat(ordered.getSeat());
        voucher.setNumber(ordered.getNumber());
        return voucher;
    }

    /**
     * 选座图视角：以 {@code screening_seat} 为唯一占用口径（锁定 + 已售都算占用），
     * 每个座位一条记录；若该场次的座位库存尚未初始化，则回退到订单口径。
     *
     * <p>不能直接把 {@code Ordered} 实体发给前端 —— 那等于把该场次所有订单的
     * 订单号、用户 ID、金额都给到任意登录用户。归属判定只看 JWT 里的 userId，
     * 只有本人的座位才带继续支付/取票所需的字段。
     */
    public List<SeatOccupancyView> selectSeatOccupancy(Integer recordId, Integer tokenUserId) {
        List<Ordered> activeOrders = orderedMapper.selectActiveByRecordId(recordId, STATUS);
        // 座位 → 订单：库存表只记"谁锁的/是否售出"，订单号/金额还要回到订单上取
        Map<String, Ordered> seatToOrder = new HashMap<>();
        for (Ordered ordered : activeOrders) {
            for (String seat : splitSeats(ordered.getSeat())) {
                seatToOrder.putIfAbsent(seat, ordered);
            }
        }
        List<ScreeningSeat> inventory = screeningSeatMapper.selectByRecordId(recordId);
        if (inventory == null || inventory.isEmpty()) {
            // 兼容：老场次/新影厅还没生成库存行时，保持原订单口径，选座图不至于全空
            return activeOrders.stream()
                    .map(ordered -> toSeatOccupancy(ordered.getSeat(), ordered, tokenUserId))
                    .toList();
        }
        List<SeatOccupancyView> views = new ArrayList<>();
        for (ScreeningSeat seat : inventory) {
            if (seat.getStatus() == null || seat.getStatus() == ScreeningSeat.STATUS_AVAILABLE) {
                continue;
            }
            views.add(toSeatOccupancy(seat.getSeatCode(), seatToOrder.get(seat.getSeatCode()), tokenUserId));
        }
        return views;
    }

    /** 订单里的座位串是逗号分隔（历史数据可能全角），切成单个座位号 */
    private static List<String> splitSeats(String rawSeats) {
        if (rawSeats == null || rawSeats.isBlank()) {
            return List.of();
        }
        return List.of(rawSeats.replace('，', ',').split(","));
    }

    private SeatOccupancyView toSeatOccupancy(String seatCode, Ordered ordered, Integer tokenUserId) {
        SeatOccupancyView view = new SeatOccupancyView();
        view.setSeat(seatCode);
        boolean mine = ordered != null && tokenUserId != null && tokenUserId.equals(ordered.getUserId());
        view.setMine(mine);
        if (!mine) {
            return view;
        }
        view.setOrderId(ordered.getId());
        view.setOrders(ordered.getOrders());
        view.setStatus(ordered.getStatus());
        view.setTotal(ordered.getTotal());
        view.setPendingTimeoutAt(ordered.getPendingTimeoutAt());
        return view;
    }

    /**
     * 今日票房：今天支付的售票收入合计（元）+ 统计时刻。
     * 日期边界与统计时刻都取自数据库时钟，避免"边界按一台钟切、时间戳按另一台钟写"。
     * 口径与状态集合见 OrderedMapper.selectTodayPaidRevenue 的注释。
     */
    public TodayRevenueView todayPaidRevenue() {
        return orderedMapper.selectTodayPaidRevenue();
    }

    public int countByFilmId(Integer filmId) {
        return orderedMapper.countByFilmId(filmId);
    }

    public int countByCinemaId(Integer cinemaId) {
        return orderedMapper.countByCinemaId(cinemaId);
    }

    public int countByRoomId(Integer roomId) {
        return orderedMapper.countByRoomId(roomId);
    }

    public int countByRecordId(Integer recordId) {
        return orderedMapper.countByRecordId(recordId);
    }

    public int countByUserId(Integer userId) {
        return orderedMapper.countByUserId(userId);
    }

    public Ordered selectByIdScoped(Integer id, String role, Integer userId) {
        Ordered ordered = selectById(id);
        ensureOrderAccess(ordered, role, userId);
        return ordered;
    }

    public void updateScoped(Ordered ordered, String role, Integer userId) {
        throw new CustomException(
                ErrorCode.FORBIDDEN,
                "订单不支持通用更新，请使用取消/支付/取票/退票显式接口"
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteScoped(Integer id, String role, Integer userId) {
        Ordered ordered = selectById(id);
        ensureOrderAccess(ordered, role, userId);
        ensureDeletable(ordered);
        orderedMapper.deleteById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteBatchScoped(List<Integer> ids, String role, Integer userId) {
        if (ids == null || ids.isEmpty()) {
            throw new CustomException(ErrorCode.PARAM_INVALID, "请选择订单");
        }
        List<String> blocked = new ArrayList<>();
        for (Integer id : ids) {
            Ordered ordered = selectById(id);
            ensureOrderAccess(ordered, role, userId);
            try {
                ensureDeletable(ordered);
            } catch (CustomException e) {
                if (!BatchDeleteGuard.isBlocked(e)) {
                    throw e;
                }
                blocked.add("订单 #" + id + "「" + ordered.getOrders() + "」：" + e.getMsg());
            }
        }
        BatchDeleteGuard.throwIfBlocked(blocked);
        orderedMapper.deleteBatch(ids);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Integer id, String role, Integer userId) {
        Ordered ordered = orderedMapper.selectByIdForUpdate(id);
        ensureOrderAccess(ordered, role, userId);
        if (!OrderStatus.PENDING_PAYMENT.equals(ordered.getStatus())) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "当前状态不允许取消订单");
        }
        Ordered update = new Ordered();
        update.setId(id);
        update.setStatus(OrderStatus.CANCELLED);
        update.setClearPendingTimeout(true);
        orderedMapper.updateById(update);
        // ===== 取消时释放已锁定座位 =====
        screeningSeatMapper.releaseByOrderNo(ordered.getOrders());
        if (outboxService != null) {
            ordered.setStatus(OrderStatus.CANCELLED);
            outboxService.record(OutboxEvent.TYPE_ORDER_CANCELLED, ordered);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void pickupOrder(Integer id, String role, Integer userId) {
        Ordered ordered = orderedMapper.selectByIdForUpdate(id);
        ensureOrderAccess(ordered, role, userId);
        // 柜台通路只对 CINEMA 开放。取票记录的是"影院把票交到顾客手里"这一物理事实，
        // 能如实断言的只有放映该场次的影院；而 ADMIN 在 ensureOrderAccess 里不受 cinemaId 约束，
        // 放行它等于"一键把任意用户的票记为已取"，且本方法不记录操作人、事后无法追溯。
        // 又因「已取票」是终态、没有任何出口，这个能力连纠错用途都没有，只剩伪造一条路。
        //
        // 写成白名单而不是再补一条"拒 ADMIN"：denylist 在新增角色时会静默把取票能力
        // 一起授予新角色，这正是 MarkService 那轮"只有前端按钮在守"的同一种漏。
        //
        // 用户的自助通路是 redeemByCode（凭取票码在取票大厅核销），两者共用同一个状态迁移。
        if (!RoleEnum.CINEMA.name().equals(role)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "取票为影院柜台操作，请到取票大厅凭取票码自助取票");
        }
        if (!OrderStatus.PENDING.equals(ordered.getStatus())) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT, "当前状态不允许取票");
        }
        Ordered update = new Ordered();
        update.setId(id);
        update.setStatus(OrderStatus.PICKED_UP);
        update.setClearPendingTimeout(true);
        orderedMapper.updateById(update);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelExpiredPendingOrders() {
        List<Ordered> expired = orderedMapper.selectExpiredPendingOrders(STATUS);
        if (expired.isEmpty()) {
            return;
        }
        List<Integer> ids = expired.stream().map(Ordered::getId).toList();
        orderedMapper.batchCancelExpiredOrders(ids, STATUS);
        // ===== 释放所有过期订单锁定的座位 =====
        for (Ordered o : expired) {
            screeningSeatMapper.releaseByOrderNo(o.getOrders());
            if (outboxService != null) {
                o.setStatus(OrderStatus.CANCELLED);
                outboxService.record(OutboxEvent.TYPE_ORDER_CANCELLED, o);
            }
        }
    }

    /**
     * 删除守卫：只有「已取消 / 已退票」的终态废单可以物理删除。
     * 待支付/待取票/已取票订单一律拒绝，把「删订单当免费退票用」的旁路堵死。
     */
    private void ensureDeletable(Ordered ordered) {
        if (ordered == null || !DELETABLE_STATUSES.contains(ordered.getStatus())) {
            throw new CustomException(ErrorCode.BUSINESS_CONFLICT,
                    "仅「已取消 / 已退票」订单可删除；已支付订单请走退票流程");
        }
    }

    /** 资金操作使用的订单金额。金额缺失属存量脏数据，给出可读提示而不是抛 NPE */
    private BigDecimal orderAmount(Ordered ordered) {
        if (ordered.getTotal() == null) {
            throw new CustomException(ErrorCode.SYSTEM_ERROR, "订单缺失金额，无法完成资金操作，请联系管理员");
        }
        return ordered.getTotal();
    }

    private void ensureOrderAccess(Ordered ordered, String role, Integer userId) {
        if (ordered == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (RoleEnum.ADMIN.name().equals(role)) {
            return;
        }
        if (RoleEnum.USER.name().equals(role) && userId != null && userId.equals(ordered.getUserId())) {
            return;
        }
        if (RoleEnum.CINEMA.name().equals(role) && userId != null && userId.equals(ordered.getCinemaId())) {
            return;
        }
        throw new CustomException(ErrorCode.FORBIDDEN, "无权操作该订单");
    }

    /**
     * 规范化并校验座位号：合法范围取自所属影厅的 seat_rows/seat_cols，
     * 影厅已删除或未配置座位数时退回 8×8（与 room 表列默认值一致）。
     */
    private List<String> normalizeSeats(String rawSeats, Integer roomId) {
        // 解析、范围校验、规范化与去重统一由 SeatParser 负责；这里只负责取影厅
        Room room = roomId == null ? null : roomMapper.selectById(roomId);
        return SeatParser.normalize(rawSeats, room);
    }

    /**
     * 把库里的票价文本解析为金额。库列是 {@code decimal(10,2)}，因此这里
     * <b>几乎不可能</b>拿到非法值 —— 约束在数据库层已经生效。
     *
     * <p>但它仍然必须存在且必须与 {@code RecordService.parsePrice} 同口径：
     * <ol>
     *   <li>{@code Record.price} 在实体里是 {@code String}（前端提交与回显都按字符串走），
     *       而非空解析失败在本项目里是真实可发生的（见 RecordService 的录入校验）；</li>
     *   <li>一旦库被改成宽松模式、或某天换成 varchar 列，这条就是唯一的防线。
     *       届时若不在这里拦住，异常会漏成 500「系统错误」。</li>
     * </ol>
     *
     * @see PriceParser
     */
    private BigDecimal parsePrice(String price) {
        return PriceParser.parse(price);
    }

    /** 订单号：无前缀；生成规则统一在 {@link OrderNumber}（与充值单号同一份实现） */
    private String generateOrderNo() {
        return OrderNumber.generate("");
    }

    /**
     * 取票码：4-4 分组的 8 位随机码，形如 {@code 8F3A-2C71}。
     * 字母表剔除 I/L/O/0/1 —— 人工从手机抄到自助机上时这几个最容易看错。
     * 空间 31^8 ≈ 8.5e11，不做碰撞重试：payOrder 里扣款与出票同一事务，
     * 唯一键冲突会让事务进入 rollback-only，同一事务内根本无法重试；
     * 真要重试就得把扣款拆成独立事务，风险远大于收益。pickup_code 上的唯一索引是兜底。
     *
     * <p><b>随机源必须是 {@link SecureCodes}</b>（{@link java.security.SecureRandom}）：
     * 取票码是匿名核销接口的唯一凭证，{@code ThreadLocalRandom} 可预测，
     * 「空间够大」不等于「猜不到」。这条不是风格问题，是凭证强度问题。
     */
    private String generatePickupCode() {
        String body = SecureCodes.of(PICKUP_CODE_ALPHABET, PICKUP_CODE_LENGTH);
        return body.substring(0, PICKUP_CODE_LENGTH / 2)
                + PICKUP_CODE_GROUP_SEPARATOR
                + body.substring(PICKUP_CODE_LENGTH / 2);
    }

    /**
     * 把用户输入的取票码归一成库里的存储形态 {@code XXXX-XXXX}，因此粘贴带不带横杠、
     * 带不带空格、大小写混写都能核销。归一后是精确等值查询，pickup_code 的唯一索引照常命中
     * —— 若改成 {@code WHERE REPLACE(pickup_code,'-','') = ?}，列上套函数会让索引失效。
     *
     * 字符集刻意放宽到 [A-Z0-9]：生成用的字母表更窄（去混淆），但历史上给存量待取票订单
     * 补过含 0/1 的十六进制码，收窄校验会把那批码挡在门外。只对"生成"收窄，对"输入"放宽。
     * 长度不符即判为无效，返回空串由调用方统一报「取票码无效」。
     */
    private static String normalizePickupCode(String raw) {
        if (raw == null) {
            return "";
        }
        String compact = raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (compact.length() != PICKUP_CODE_LENGTH) {
            return "";
        }
        return compact.substring(0, PICKUP_CODE_LENGTH / 2)
                + PICKUP_CODE_GROUP_SEPARATOR
                + compact.substring(PICKUP_CODE_LENGTH / 2);
    }
}
