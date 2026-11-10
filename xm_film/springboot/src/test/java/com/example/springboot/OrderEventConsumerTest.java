package com.example.springboot;

import com.example.springboot.entity.OutboxEvent;
import com.example.springboot.mapper.OrderNotificationMapper;
import com.example.springboot.service.OrderEventConsumer;
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
 * 消费侧真实业务落库 + 幂等（任务 7）：
 * 同一事件消费两次只产生一条通知，重复投递不会重复生效。
 */
@SpringBootTest
@ActiveProfiles("ci")
class OrderEventConsumerTest {

    @Autowired
    OrderEventConsumer consumer;

    @Autowired
    OrderNotificationMapper notificationMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String eventId;
    private String orderNo;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        eventId = "evt-" + suffix;
        orderNo = "ORDER-" + suffix;
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM order_notification WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM mq_consume_log WHERE event_id = ?", eventId);
    }

    @Test
    void consumeWritesNotificationOnceEvenIfDeliveredTwice() {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setEventType(OutboxEvent.TYPE_ORDER_PAID);
        event.setAggregateId(orderNo);
        event.setPayload("{\"userId\":8,\"orders\":\"" + orderNo
                + "\",\"seat\":\"1排1座\",\"total\":\"45.00\"}");

        consumer.consume(event);
        consumer.consume(event);

        assertThat(notificationMapper.countByOrderNo(orderNo)).isEqualTo(1);
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM order_notification WHERE event_id = ?", String.class, eventId);
        assertThat(content).contains(orderNo).contains("支付成功").contains("1排1座");
    }
}
