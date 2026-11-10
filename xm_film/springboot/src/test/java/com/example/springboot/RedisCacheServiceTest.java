package com.example.springboot;

import com.example.springboot.common.RedisCacheService;
import com.example.springboot.entity.Film;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存降级与"提交后写缓存"（任务 5）。
 */
@ExtendWith(MockitoExtension.class)
class RedisCacheServiceTest {

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> valueOperations;

    RedisCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new RedisCacheService(redis, new ObjectMapper());
    }

    /** Redis 故障：读返回 null（调用方回源数据库）、写与失效都不抛异常 */
    @Test
    void degradesQuietlyWhenRedisIsDown() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(cacheService.get("film:list:0:1", new TypeReference<Film>() {})).isNull();
        assertThat(cacheService.filmVersion()).isNull();
        cacheService.put("film:list:0:1", new Film(), Duration.ofMinutes(1));
        cacheService.bumpFilmVersion();
    }

    /** 提交前不写缓存：避免事务回滚后缓存里留下未生效的新值 */
    @Test
    void putAfterCommitWaitsForCommit() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        TransactionSynchronizationManager.initSynchronization();
        try {
            cacheService.putAfterCommit("film:detail:0:1", new Film(), Duration.ofMinutes(5));
            verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(valueOperations).set(eq("film:detail:0:1"), anyString(), any(Duration.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 无事务上下文时立即写入，行为与改造前一致 */
    @Test
    void putAfterCommitWritesImmediatelyWithoutTransaction() {
        when(redis.opsForValue()).thenReturn(valueOperations);

        cacheService.putAfterCommit("film:detail:0:2", new Film(), Duration.ofMinutes(5));

        verify(valueOperations).set(eq("film:detail:0:2"), anyString(), any(Duration.class));
    }
}
