package com.example.springboot;

import com.example.springboot.common.OrderRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 下单限流（任务 4）：Lua 脚本返回 1 放行、返回 0 拒绝；Redis 异常时 fail-open。
 */
@ExtendWith(MockitoExtension.class)
class OrderRateLimiterTest {

    @Mock
    StringRedisTemplate redis;

    OrderRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new OrderRateLimiter(redis);
    }

    @Test
    void allowsWhenScriptReturnsOne() {
        doReturn(1L).when(redis).execute(any(RedisScript.class), anyList(), any(), any());

        assertThat(limiter.tryAcquire(1)).isTrue();
    }

    @Test
    void rejectsWhenScriptReportsOverLimit() {
        doReturn(0L).when(redis).execute(any(RedisScript.class), anyList(), any(), any());

        assertThat(limiter.tryAcquire(1)).isFalse();
    }

    /** Redis 不可用时放行：限流是保护措施，不能把正常用户一起拒掉 */
    @Test
    void failsOpenWhenRedisIsDown() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any());

        assertThat(limiter.tryAcquire(1)).isTrue();
    }

    @Test
    void anonymousRequestIsNotLimited() {
        assertThat(limiter.tryAcquire(null)).isTrue();
        verifyNoInteractions(redis);
    }
}
