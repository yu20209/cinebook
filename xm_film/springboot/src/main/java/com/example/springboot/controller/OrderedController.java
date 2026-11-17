package com.example.springboot.controller;

import com.example.springboot.common.AuthContext;
import com.example.springboot.common.BaseController;
import com.example.springboot.common.Result;
import com.example.springboot.common.enums.ErrorCode;
import com.example.springboot.common.enums.PayResult;
import com.example.springboot.common.enums.RoleEnum;
import com.example.springboot.dto.request.OrderCreateRequest;
import com.example.springboot.dto.request.OrderPayRequest;
import com.example.springboot.dto.request.OrderedRequest;
import com.example.springboot.dto.response.OrderedView;
import com.example.springboot.dto.response.SeatOccupancyView;
import com.example.springboot.entity.Ordered;
import com.example.springboot.exception.CustomException;
import com.example.springboot.service.OrderedService;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.page.PageMethod;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单接口。
 *
 * <p>基础 CRUD 来自 {@link BaseController}，但建单、支付、取消、退票、取票这些
 * 有状态迁移语义的操作全部走独立端点，由 {@code OrderedService} 的状态机负责：
 * <ul>
 *   <li>{@code POST /create}：按 requestId 幂等建单并锁座（唯一允许的建单入口）；</li>
 *   <li>{@code PUT /{id}/pay|cancel|refund|pickup}：状态迁移，均校验归属与时限；</li>
 *   <li>{@code GET /seats}：选座图数据，只按座位返回占用状态，不暴露他人订单。</li>
 * </ul>
 * 所有端点都从 {@code AuthContext} 取当前用户，不信任请求体里的 userId。
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderedController extends BaseController<Ordered, OrderedRequest, OrderedView> {
    private final OrderedService orderedService;

    public OrderedController(OrderedService orderedService) {
        super(orderedService);
        this.orderedService = orderedService;
    }

    /** DTO → 实体：只做字段搬运，业务规则一律留在 service */
    @Override
    protected Ordered toEntity(OrderedRequest request) {
        Ordered ordered = new Ordered();
        ordered.setId(request.getId());
        ordered.setOrders(request.getOrders());
        ordered.setRecordId(request.getRecordId());
        ordered.setUserId(request.getUserId());
        ordered.setFilmId(request.getFilmId());
        ordered.setImg(request.getImg());
        ordered.setCinemaId(request.getCinemaId());
        ordered.setRoomId(request.getRoomId());
        ordered.setAppointment(request.getAppointment());
        ordered.setTotal(request.getTotal());
        ordered.setUnitPrice(request.getUnitPrice());
        ordered.setNumber(request.getNumber());
        ordered.setStatus(request.getStatus());
        ordered.setStart(request.getStart());
        ordered.setSeat(request.getSeat());
        ordered.setCreateTime(request.getCreateTime());
        ordered.setPendingTimeoutAt(request.getPendingTimeoutAt());
        ordered.setPayTime(request.getPayTime());
        ordered.setPayAmount(request.getPayAmount());
        ordered.setRefundTime(request.getRefundTime());
        ordered.setRefundAmount(request.getRefundAmount());
        ordered.setPickupCode(request.getPickupCode());
        ordered.setUserName(request.getUserName());
        ordered.setFilmName(request.getFilmName());
        ordered.setCinemaName(request.getCinemaName());
        ordered.setRoomName(request.getRoomName());
        return ordered;
    }

    /** 实体 → 视图：join 派生的 filmName/cinemaName/roomName 也在实体上，直接带出 */
    @Override
    protected OrderedView toView(Ordered ordered) {
        OrderedView view = new OrderedView();
        view.setId(ordered.getId());
        view.setOrders(ordered.getOrders());
        view.setRecordId(ordered.getRecordId());
        view.setUserId(ordered.getUserId());
        view.setFilmId(ordered.getFilmId());
        view.setImg(ordered.getImg());
        view.setCinemaId(ordered.getCinemaId());
        view.setRoomId(ordered.getRoomId());
        view.setAppointment(ordered.getAppointment());
        view.setTotal(ordered.getTotal());
        view.setUnitPrice(ordered.getUnitPrice());
        view.setNumber(ordered.getNumber());
        view.setStatus(ordered.getStatus());
        view.setStart(ordered.getStart());
        view.setSeat(ordered.getSeat());
        view.setCreateTime(ordered.getCreateTime());
        view.setPendingTimeoutAt(ordered.getPendingTimeoutAt());
        view.setPayTime(ordered.getPayTime());
        view.setPayAmount(ordered.getPayAmount());
        view.setRefundTime(ordered.getRefundTime());
        view.setRefundAmount(ordered.getRefundAmount());
        view.setPickupCode(ordered.getPickupCode());
        view.setUserName(ordered.getUserName());
        view.setFilmName(ordered.getFilmName());
        view.setCinemaName(ordered.getCinemaName());
        view.setRoomName(ordered.getRoomName());
        return view;
    }

    /** 列表：按当前角色收敛数据范围（USER 只看自己，CINEMA 只看本影院） */
    @Override
    @GetMapping
    public Result<List<OrderedView>> list(OrderedRequest query) {
        limitList();
        Ordered entity = toEntity(query);
        orderedService.applyScope(entity, AuthContext.role(), AuthContext.userId());
        return Result.success(orderedService.selectAll(entity).stream().map(this::toView).toList());
    }

    /** 分页列表：作用域与 list 一致，分页参数由 PageHelper 接管 */
    @Override
    @GetMapping("/page")
    public Result<PageInfo<OrderedView>> page(OrderedRequest query,
                       @RequestParam(defaultValue = "1") Integer pageNum,
                       @RequestParam(defaultValue = "10") Integer pageSize) {
        Ordered entity = toEntity(query);
        orderedService.applyScope(entity, AuthContext.role(), AuthContext.userId());
        PageMethod.startPage(pageNum, pageSize);
        return Result.success(toPageView(new PageInfo<>(orderedService.selectAll(entity))));
    }

    /** 单条查询：越权访问由 service 的 ensureOrderAccess 统一拒绝 */
    @Override
    @GetMapping("/{id}")
    public Result<OrderedView> getById(@PathVariable Integer id) {
        Ordered ordered = orderedService.selectByIdScoped(id, AuthContext.role(), AuthContext.userId());
        return Result.success(toView(ordered));
    }

    /**
     * 关闭基类的 POST 入口：建单必须带 requestId、走锁座流程，
     * 裸插入会绕过座位库存，因此这里直接拒绝并提示正确端点。
     */
    @Override
    @PostMapping
    public Result<Void> add(@RequestBody OrderedRequest request) {
        throw new CustomException(ErrorCode.PARAM_INVALID, "请使用 /api/v1/orders/create 创建订单");
    }

    /**
     * 建单（唯一入口）：requestId 必填，服务端据此做幂等；座位级锁在同一事务内完成。
     * 重复提交返回原订单，不会重复锁座。
     */
    @PostMapping("/create")
    public Result<OrderedView> create(@Valid @RequestBody OrderCreateRequest request) {
        Ordered ordered = new Ordered();
        ordered.setRecordId(request.getRecordId());
        ordered.setSeat(request.getSeat());
        ordered.setRequestId(request.getRequestId());
        orderedService.createOrder(ordered, AuthContext.role(), AuthContext.userId());
        return Result.success(toView(ordered));
    }

    /** 更新：状态字段不可由客户端直接改，只允许改备注类信息（见 OrderedService.update） */
    @Override
    @PutMapping
    public Result<Void> update(@RequestBody OrderedRequest request) {
        orderedService.updateScoped(toEntity(request), AuthContext.role(), AuthContext.userId());
        return Result.success();
    }

    /** 取消：仅待支付订单可取消，取消同时释放该单锁定的座位 */
    @PutMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Integer id) {
        orderedService.cancelOrder(id, AuthContext.role(), AuthContext.userId());
        return Result.success();
    }

    /** 柜台取票：仅影院端操作，核销后订单转为已取票 */
    @PutMapping("/{id}/pickup")
    public Result<Void> pickup(@PathVariable Integer id) {
        orderedService.pickupOrder(id, AuthContext.role(), AuthContext.userId());
        return Result.success();
    }

    /**
     * 支付：余额扣款 + 座位转已售 + 生成取票码 + 写 Outbox 事件，全部在同一事务内。
     * 超时取消不抛异常（否则会回滚"已取消"状态），由这里翻译成业务错误。
     */
    @PutMapping("/{id}/pay")
    public Result<Void> pay(@PathVariable Integer id, @Valid @RequestBody OrderPayRequest request) {
        if (orderedService.payOrder(id, AuthContext.role(), AuthContext.userId(), request.getPayPassword())
                == PayResult.TIMEOUT_CANCELLED) {
            // 超时取消必须已落库，因此服务层不抛异常回滚，由此处翻译成业务错误返回客户端
            return Result.error(ErrorCode.BUSINESS_CONFLICT, "支付超时，订单已自动取消");
        }
        return Result.success();
    }

    /** 退票：仅待取票且未过放映前 60 分钟，退款入账与座位释放同事务 */
    @PutMapping("/{id}/refund")
    public Result<Void> refund(@PathVariable Integer id) {
        orderedService.refundOrder(id, AuthContext.role(), AuthContext.userId());
        return Result.success();
    }

    /**
     * 选座图：以 screening_seat 为占用口径，逐座位返回状态。
     * viewerId 只在 USER 角色下传，避免影院/管理员 id 与用户 id 同号时误标"我的订单"。
     */
    @GetMapping("/seats")
    public Result<List<SeatOccupancyView>> seats(@RequestParam Integer recordId) {
        // 归属判定在后端按 JWT 完成；响应里不含他人订单的订单号/用户/金额。
        // mine 只对 USER 有意义：cinema/admin 的 id 与 user 表不同源，直接把 id 传下去
        // 会把同号用户订单误标成"我的"，泄露其订单号/金额 —— 非 USER 一律按匿名视角查。
        Integer viewerId = RoleEnum.USER.name().equals(AuthContext.role()) ? AuthContext.userId() : null;
        return Result.success(orderedService.selectSeatOccupancy(recordId, viewerId));
    }

    /** 删除：只允许删除已取消/已退票的终态订单，成交订单必须走退票 */
    @Override
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        orderedService.deleteScoped(id, AuthContext.role(), AuthContext.userId());
        return Result.success();
    }

    /** 批量删除：逐条做与单删相同的终态与归属校验，任一不通过整体拒绝 */
    @Override
    @DeleteMapping("/batch")
    public Result<Void> deleteBatch(@RequestBody List<Integer> ids) {
        orderedService.deleteBatchScoped(ids, AuthContext.role(), AuthContext.userId());
        return Result.success();
    }

}
