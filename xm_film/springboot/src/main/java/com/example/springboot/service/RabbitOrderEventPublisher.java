package com.example.springboot.service;

import com.example.springboot.entity.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * RabbitMQ 事件发布器：{@code app.order-event.publisher=rabbit} 时接管投递。
 *
 * <p>可靠性要点：
 * <ul>
 *   <li>发布确认（publisher confirm）：同步等待 broker 的 ACK，超时或 NACK 都抛异常，
 *       Outbox 会保留事件并按指数退避重试，不会误标 SENT；</li>
 *   <li>mandatory + return：交换机存在但没有队列绑定时，broker 会退回消息，
 *       CorrelationData 上的 returned 标记据此判定"投递未真正入队"，同样按失败重试；</li>
 *   <li>eventId 放进 messageId、订单号与事件类型放进 header，
 *       消费端据此重建事件并做幂等，无需依赖载荷内部结构；</li>
 *   <li>载荷直接发 Outbox 里已经序列化好的 JSON，不再二次包装。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.order-event.publisher", havingValue = "rabbit")
public class RabbitOrderEventPublisher implements OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitOrderEventPublisher.class);

    /** 等待 broker 确认的超时时间；超时按失败处理，交给 Outbox 重试 */
    private static final long CONFIRM_TIMEOUT_MILLIS = 5_000L;

    /** 消息头键名，消费端按同一份常量解析 */
    public static final String HEADER_EVENT_TYPE = "eventType";
    public static final String HEADER_ORDER_NO = "orderNo";

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;

    public RabbitOrderEventPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${app.order-event.exchange:cinebook.order.exchange}") String exchange,
            @Value("${app.order-event.routing-key:cinebook.order.created}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    @Override
    public void publish(OutboxEvent event) {
        String payload = event.getPayload() == null ? "{}" : event.getPayload();
        CorrelationData correlation = new CorrelationData(event.getEventId());
        rabbitTemplate.convertAndSend(exchange, routingKey, payload, message -> {
            MessageProperties props = message.getMessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setMessageId(event.getEventId());
            props.setHeader(HEADER_EVENT_TYPE, event.getEventType());
            props.setHeader(HEADER_ORDER_NO, event.getAggregateId());
            return message;
        }, correlation);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("等待 RabbitMQ 发布确认失败: " + e.getMessage(), e);
        }
        if (!confirm.isAck()) {
            throw new IllegalStateException("RabbitMQ 未确认消息（NACK）: " + confirm.getReason());
        }
        // mandatory 模式下被退回 = 交换机存在但没有匹配队列，消息实际已丢失
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("消息未路由到任何队列，broker 已退回: "
                    + correlation.getReturned().getReplyText());
        }
        log.info("已投递订单事件到 RabbitMQ: type={} orderNo={} eventId={}",
                event.getEventType(), event.getAggregateId(), event.getEventId());
    }
}
