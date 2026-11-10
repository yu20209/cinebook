package com.example.springboot.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

/**
 * 热点数据缓存（Redis），带"版本号"失效机制。
 *
 * <p>为什么用版本号而不是逐个删 key：
 * 影片列表的 key 由查询条件组合而成，数量不可枚举；写入时逐个删要么漏删、要么得用
 * {@code KEYS} 全库扫描（线上禁用）。改用一个全局版本号 {@code film:ver}：
 * 每次写操作把它 +1，读路径把版本号拼进 key，旧版本的 key 自然再也不会被命中，
 * 到期随 TTL 回收 —— 失效成本从 O(N) 降到 O(1)。
 *
 * <p>降级原则：Redis 出任何异常都只记日志，调用方继续走数据库。
 * 缓存是加速手段，不能成为可用性的单点。
 */
@Component
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    /** 影片域版本号 key */
    private static final String FILM_VERSION_KEY = "film:ver";
    /** 默认缓存时长：热点列表 5 分钟，足够挡住首页的重复查询，又不至于长时间不刷新 */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisCacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 读取影片域当前版本号；Redis 不可用时返回 "-", 让读路径直接降级到数据库 */
    public String filmVersion() {
        try {
            String ver = redis.opsForValue().get(FILM_VERSION_KEY);
            return ver == null ? "0" : ver;
        } catch (Exception e) {
            log.warn("读取影片缓存版本失败，降级直查数据库: {}", e.getMessage());
            return null;
        }
    }

    /** 影片数据变更后调用：版本号 +1，此前写入的缓存全部失效 */
    public void bumpFilmVersion() {
        try {
            redis.opsForValue().increment(FILM_VERSION_KEY);
        } catch (Exception e) {
            log.warn("更新影片缓存版本失败（本次改动不阻塞业务）: {}", e.getMessage());
        }
    }

    /** 读缓存；未命中或 Redis 异常时返回 null，由调用方回源数据库 */
    public <T> T get(String key, TypeReference<T> type) {
        if (key == null) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("读取缓存失败 key={}，降级直查数据库: {}", key, e.getMessage());
            return null;
        }
    }

    /** 写缓存；失败仅记日志，不影响主流程 */
    public void put(String key, Object value, Duration ttl) {
        if (key == null || value == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value),
                    ttl == null ? DEFAULT_TTL : ttl);
        } catch (Exception e) {
            log.warn("写入缓存失败 key={}（忽略）: {}", key, e.getMessage());
        }
    }

    /**
     * 把缓存写操作推迟到事务提交之后执行：事务回滚时不会在缓存里留下未生效的新值。
     * 无事务上下文时立即执行（读路径行为不变）。
     */
    public void afterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runQuietly(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runQuietly(action);
            }
        });
    }

    /** 提交后再写缓存 */
    public void putAfterCommit(String key, Object value, Duration ttl) {
        afterCommit(() -> put(key, value, ttl));
    }

    /** 提交后再让影片域缓存整体失效 */
    public void bumpFilmVersionAfterCommit() {
        afterCommit(this::bumpFilmVersion);
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("事务提交后的缓存操作失败（忽略）: {}", e.getMessage());
        }
    }
}
