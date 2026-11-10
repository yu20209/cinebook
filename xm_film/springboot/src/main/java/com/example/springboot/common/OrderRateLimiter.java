package com.example.springboot.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 下单接口限流（Redis 计数器）。
 *
 * <p>为什么要限流：下单是写操作，且会占用座位库存。恶意或前端异常重试造成的
 * 高频提交会把数据库连接与座位行都压在少数用户身上。限流挡在业务逻辑之前，
 * 成本只是一次 Redis INCR。
 *
 * <p>降级策略：Redis 不可用时**放行**（fail-open）。限流是保护措施，
 * 不能因为限流组件故障就把正常用户全拒掉 —— 真正的数据一致性由数据库条件更新保证。
 */
@Component
public class OrderRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(OrderRateLimiter.class);

    /** 单用户每分钟允许的下单尝试次数 */
    private static final int LIMIT_PER_MINUTE = 20;

    private final StringRedisTemplate redis;

    public OrderRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true 表示允许本次请求；false 表示已被限流
     */
    public boolean tryAcquire(Integer userId) {
        if (userId == null) {
            return true;
        }
        // 按分钟分桶：{前缀}:{用户}:{分钟序号}，天然滑动到下一分钟即自动重置
        long minuteBucket = System.currentTimeMillis() / 60_000;
        String key = "rl:order:" + userId + ":" + minuteBucket;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 首次计数时设置过期，避免 key 永久堆积
                redis.expire(key, Duration.ofSeconds(70));
            }
            return count == null || count <= LIMIT_PER_MINUTE;
        } catch (Exception e) {
            log.warn("下单限流检查失败，按放行处理: {}", e.getMessage());
            return true;
        }
    }
}
