package com.example.springboot.service;

import com.example.springboot.entity.OrderNotification;
import com.example.springboot.entity.OutboxEvent;
import com.example.springboot.mapper.MqConsumeLogMapper;
import com.example.springboot.mapper.OrderNotificationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单事件消费者（幂等 + 真实业务落库）。
 *
 * <p>投递语义是"至少一次"，因此消费者必须自己保证重复事件只生效一次。
 * 两级幂等：
 * <ol>
 *   <li>{@code mq_consume_log} 的 {@code UNIQUE(consumer_name, event_id)}：重复事件影响 0 行，直接跳过；</li>
 *   <li>{@code order_notification} 的 {@code UNIQUE(event_id)}：即使并发穿透第一层，也不会重复落库。</li>
 * </ol>
 *
 * <p>消费侧不是"只打日志"：每消费一条订单事件就写一条通知记录，
 * 与消费日志在同一个事务里提交 —— 要么都成功，要么都回滚重投。
 */
@Service
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    private static final String CONSUMER_NAME = "order-event";

    private final MqConsumeLogMapper consumeLogMapper;
    private final OrderNotificationMapper notificationMapper;
    private final ObjectMapper objectMapper;

    public OrderEventConsumer(MqConsumeLogMapper consumeLogMapper,
                              OrderNotificationMapper notificationMapper,
                              ObjectMapper objectMapper) {
        this.consumeLogMapper = consumeLogMapper;
        this.notificationMapper = notificationMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void consume(OutboxEvent event) {
        int inserted = consumeLogMapper.insertIgnore(CONSUMER_NAME, event.getEventId());
        if (inserted == 0) {
            log.info("重复事件已忽略: eventId={}", event.getEventId());
            return;
        }
        OrderNotification notification = buildNotification(event);
        int written = notificationMapper.insertIgnore(notification);
        log.info("消费订单事件成功: type={} orderNo={} 通知落库={} 行",
                event.getEventType(), notification.getOrderNo(), written);
    }

    /** 从事件载荷（订单 JSON 快照）构造通知；载荷不可解析时降级为只带订单号 */
    private OrderNotification buildNotification(OutboxEvent event) {
        String orderNo = event.getAggregateId();
        Integer userId = null;
        String seat = null;
        String total = null;
        try {
            if (event.getPayload() != null && !event.getPayload().isBlank()) {
                JsonNode node = objectMapper.readTree(event.getPayload());
                if (node.hasNonNull("userId")) {
                    userId = node.get("userId").asInt();
                }
                if (node.hasNonNull("orders") && !node.get("orders").asText().isBlank()) {
                    orderNo = node.get("orders").asText();
                }
                seat = textOrNull(node, "seat");
                total = textOrNull(node, "total");
            }
        } catch (Exception e) {
            log.warn("事件载荷解析失败，通知降级为仅订单号 orderNo={}: {}", orderNo, e.getMessage());
        }

        OrderNotification notification = new OrderNotification();
        notification.setEventId(event.getEventId());
        notification.setEventType(event.getEventType());
        notification.setOrderNo(orderNo == null ? "unknown" : orderNo);
        notification.setUserId(userId);
        notification.setContent(truncate(buildContent(event.getEventType(), notification.getOrderNo(), seat, total)));
        return notification;
    }

    private static String buildContent(String eventType, String orderNo, String seat, String total) {
        String action;
        if (OutboxEvent.TYPE_ORDER_PAID.equals(eventType)) {
            action = "支付成功";
        } else if (OutboxEvent.TYPE_ORDER_CANCELLED.equals(eventType)) {
            action = "已取消";
        } else if (OutboxEvent.TYPE_ORDER_REFUNDED.equals(eventType)) {
            action = "已退款";
        } else {
            action = "状态变更（" + eventType + "）";
        }
        StringBuilder sb = new StringBuilder("订单 ").append(orderNo).append(' ').append(action);
        if (seat != null && !seat.isBlank()) {
            sb.append("，座位 ").append(seat);
        }
        if (total != null && !total.isBlank()) {
            sb.append("，金额 ").append(total).append(" 元");
        }
        return sb.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String truncate(String content) {
        return content.length() > OrderNotification.CONTENT_MAX_LENGTH
                ? content.substring(0, OrderNotification.CONTENT_MAX_LENGTH)
                : content;
    }
}
