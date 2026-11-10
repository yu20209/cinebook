package com.example.springboot;

import com.example.springboot.entity.OutboxEvent;
import com.example.springboot.mapper.OutboxEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 多实例认领（验收矩阵 T15）。
 *
 * <p>认领模型要同时满足两件事：
 * 并发实例取到的事件互不重叠；实例崩溃/超时后租约到期，事件能被其它实例接手，
 * 且原实例的迟到确认不会生效。
 */
@SpringBootTest
@ActiveProfiles("ci")
class OutboxClaimTest {

    @Autowired
    OutboxEventMapper outboxEventMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanBefore() {
        jdbcTemplate.update("DELETE FROM outbox_event");
    }

    @AfterEach
    void cleanAfter() {
        jdbcTemplate.update("DELETE FROM outbox_event");
    }

    /** 两个实例并发派发：各自认领不同事件，不会重复处理同一条 */
    @Test
    void twoInstancesNeverClaimSameEvent() {
        String first = insertEvent();
        String second = insertEvent();

        assertThat(outboxEventMapper.claimPending("inst-a", 60, 10)).isEqualTo(2);
        assertThat(outboxEventMapper.claimPending("inst-b", 60, 10)).isZero();

        assertThat(outboxEventMapper.selectClaimedBy("inst-a"))
                .extracting(OutboxEvent::getEventId)
                .containsExactlyInAnyOrder(first, second);
        assertThat(outboxEventMapper.selectClaimedBy("inst-b")).isEmpty();
    }

    /** 租约到期后另一个实例可以接手，原实例不能再标记成功 */
    @Test
    void expiredLeaseCanBeReclaimedAndOldOwnerCannotConfirm() {
        String eventId = insertEvent();
        assertThat(outboxEventMapper.claimPending("inst-a", 60, 10)).isEqualTo(1);
        Integer id = idOf(eventId);

        // 模拟 inst-a 崩溃：租约已过期
        jdbcTemplate.update("UPDATE outbox_event SET claimed_until = NOW() - INTERVAL 1 SECOND WHERE id = ?", id);

        assertThat(outboxEventMapper.claimPending("inst-b", 60, 10)).isEqualTo(1);
        assertThat(outboxEventMapper.selectClaimedBy("inst-b"))
                .extracting(OutboxEvent::getId)
                .containsExactly(id);

        // 迟到的旧实例确认必须失败，只有新认领者能完结
        assertThat(outboxEventMapper.markSent(id, "inst-a")).isZero();
        assertThat(outboxEventMapper.markSent(id, "inst-b")).isEqualTo(1);
    }

    /** 失败退回 PENDING 并累计重试次数，退避期内不会被重复认领 */
    @Test
    void failedDispatchBacksOffBeforeRetry() {
        String eventId = insertEvent();
        assertThat(outboxEventMapper.claimPending("inst-a", 60, 10)).isEqualTo(1);
        Integer id = idOf(eventId);

        assertThat(outboxEventMapper.markFailed(id, "inst-a", "broker down", 10)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT retry_count FROM outbox_event WHERE id = ?", Integer.class, id)).isEqualTo(1);
        // 退避期内不可再认领
        assertThat(outboxEventMapper.claimPending("inst-a", 60, 10)).isZero();

        jdbcTemplate.update("UPDATE outbox_event SET next_retry_at = NOW() - INTERVAL 1 SECOND WHERE id = ?", id);
        assertThat(outboxEventMapper.claimPending("inst-a", 60, 10)).isEqualTo(1);
    }

    private String insertEvent() {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO outbox_event "
                        + "(event_id, event_type, aggregate_id, payload, status, retry_count, next_retry_at) "
                        + "VALUES (?, 'ORDER_PAID', ?, '{}', 'PENDING', 0, NOW())",
                eventId, "ORD-" + eventId);
        return eventId;
    }

    private Integer idOf(String eventId) {
        return jdbcTemplate.queryForObject("SELECT id FROM outbox_event WHERE event_id = ?", Integer.class, eventId);
    }
}
