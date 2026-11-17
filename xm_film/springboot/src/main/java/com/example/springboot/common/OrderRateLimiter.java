package com.example.springboot.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 下单接口限流（Redis + Lua 原子计数器）。
 *
 * <p>为什么要限流：下单是写操作，且会占用座位库存。恶意或前端异常重试造成的
 * 高频提交会把数据库连接与座位行都压在少数用户身上。限流挡在业务逻辑之前，
 * 成本只是一次 Redis EVAL。
 *
 * <p>为什么必须用 Lua：INCR 与 EXPIRE 分两条命令发时，若进程在两条命令之间退出，
 * 这个 key 就永远没有过期时间 —— 该用户的下单会一直被拒，直到有人手动删 key。
 * Lua 脚本在 Redis 内单线程原子执行，计数与过期要么都生效、要么都不生效。
 *
 * <p>降级策略：Redis 不可用或脚本执行异常时**放行**（fail-open）。限流是保护措施，
 * 不能因为限流组件故障就把正常用户全拒掉 —— 真正的数据一致性由数据库条件更新保证。
 */
@Component
public class OrderRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(OrderRateLimiter.class);

    /** 单用户每分钟允许的下单尝试次数 */
    private static final int LIMIT_PER_MINUTE = 20;

    /** 计数窗口：比一分钟略长，跨分钟边界时旧 key 自然过期，不会永久堆积 */
    private static final long WINDOW_MILLIS = 70_000L;

    /**
     * KEYS[1] = 计数 key；ARGV[1] = 窗口毫秒数；ARGV[2] = 上限。
     * 首次计数时设置 PEXPIRE，超限返回 0，允许返回 1。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
                    //1. 尝试对 KEYS[1] 进行自增（INCR），并将结果赋值给 current
            "local current = redis.call('INCR', KEYS[1]) "
                    //2. 如果 current 等于 1，说明这是第一次访问（即刚刚创建了这个 key）
                    // -- 给这个 key 设置过期时间。ARGV[1] 是 Java 传进来的过期时间参数
                    + "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    //3. 如果 current 大于阈值（ARGV[2] 是 Java 传进来的最大允许次数），返回 0（代表被限流）
                    + "if current > tonumber(ARGV[2]) then return 0 end "
                    //4. 否则返回 1，表示允许
                    + "return 1",
            Long.class);

    private final StringRedisTemplate redis;

    public OrderRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true 表示允许本次请求；false 表示已被限流
     */
    public boolean tryAcquire(Integer userId) {
        //如果连用户 ID 都没有（比如只是个普通的游客），系统抓不到特征，一般就不做严格限制，直接放行。
        if (userId == null) {
            return true;
        }
        // 按分钟分桶：{前缀}:{用户}:{分钟序号}，天然滑动到下一分钟即自动重置
        //System.currentTimeMillis() 是当前时间戳（毫秒）。
        // 除以 60_000（一分钟的毫秒数），得到的就是“当前是历史上的第几分钟”。
        long minuteBucket = System.currentTimeMillis() / 60_000;
        String key = "rl:order:" + userId + ":" + minuteBucket;
        try {
            Long allowed = redis.execute(ACQUIRE_SCRIPT, List.of(key),
                    String.valueOf(WINDOW_MILLIS), String.valueOf(LIMIT_PER_MINUTE));
            //Lua 逻辑：返回 1 代表放行，返回 0 代表拦截。
            //如果返回了 null（极少见，比如网络异常），也按放行处理，避免误杀正常用户。
            return allowed == null || allowed == 1L;
        } catch (Exception e) {
            //兜底策略：降级.当限流组件本身出问题时，我们要“降级放行”
            //因为限流主要是保护系统的措施，并不是保证座位数据正确性的最终手段。
            //即使 Redis 故障，后面的 MySQL 条件更新仍然能够保证座位不会被重复锁定。
            log.warn("下单限流检查失败，按放行处理: {}", e.getMessage());
            return true;
        }
    }
}
