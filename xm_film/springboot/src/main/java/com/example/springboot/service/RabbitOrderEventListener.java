package com.example.springboot.service;

import com.example.springboot.entity.OutboxEvent;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * RabbitMQ 订单事件监听器（手动 ACK + 消费幂等 + 失败重试 + 死信兜底）。
 *
 * <p>处理流程：
 * <ol>
 *   <li>消费服务 {@link OrderEventConsumer#consume} 自带两级幂等（消费日志 + 通知唯一键），
 *       重复投递不会重复生效；</li>
 *   <li>业务异常时在监听器内重试 {@value #MAX_ATTEMPTS} 次（每次都是独立事务，失败会回滚）；</li>
 *   <li>重试耗尽后 basicNack(requeue=false)，消息经队列的 x-dead-letter-exchange 进入死信队列，
 *       避免无限重回队列打爆消费者，同时保留现场便于排查；</li>
 *   <li>成功后 basicAck。只有真正落库成功才确认，保证"至少一次"语义。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "app.order-event.publisher", havingValue = "rabbit")
public class RabbitOrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitOrderEventListener.class);

    /** 单条消息最多处理次数（含首次） */
    private static final int MAX_ATTEMPTS = 3;

    private final OrderEventConsumer consumer;

    public RabbitOrderEventListener(OrderEventConsumer consumer) {
        this.consumer = consumer;
    }

    @RabbitListener(queues = "${app.order-event.queue:cinebook.order.queue}", ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        OutboxEvent event;
        try {
            event = toEvent(message);
        } catch (Exception e) {
            // 消息本身不可解析：不可能通过重试变好，直接进死信
            log.error("订单事件消息解析失败，转入死信队列: {}", e.getMessage());
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                consumer.consume(event);
                channel.basicAck(deliveryTag, false);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("订单事件消费失败（第 {}/{} 次）eventId={}: {}",
                        attempt, MAX_ATTEMPTS, event.getEventId(), e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepBeforeRetry(attempt);
                }
            }
        }
        // 重试耗尽：拒绝且不重回队列 → 由队列绑定的死信交换机接管
        channel.basicNack(deliveryTag, false, false);
        log.error("订单事件重试耗尽，已转入死信队列: eventId={}", event.getEventId(), lastError);
    }

    /** 从消息头/属性重建事件：eventId 走 messageId，订单号与类型走 header，载荷即消息体 */
    private static OutboxEvent toEvent(Message message) {
        MessageProperties props = message.getMessageProperties();
        String eventId = props.getMessageId();
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("消息缺少 messageId（事件编号）");
        }
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setEventType(header(props, RabbitOrderEventPublisher.HEADER_EVENT_TYPE));
        event.setAggregateId(header(props, RabbitOrderEventPublisher.HEADER_ORDER_NO));
        event.setPayload(new String(message.getBody(), StandardCharsets.UTF_8));
        return event;
    }

    private static String header(MessageProperties props, String key) {
        Object value = props.getHeaders().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(300L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
