package com.example.springboot.service;

import com.example.springboot.entity.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地事件发布器（默认实现）：把事件直接交给消费者处理。
 *
 * <p>没有部署消息中间件时使用它，保证 Outbox → 消费 的完整链路仍然可跑通、可验证。
 * 接入 RabbitMQ 时把 {@code app.order-event.publisher} 配成 {@code rabbit}，
 * 本实现即不再注册，由 MQ 版实现接管。
 *
 * <p>注意：这里刻意不用 {@code @ConditionalOnMissingBean}——它只在自动配置类里可靠，
 * 写在被组件扫描的类上会因扫描顺序不确定而把自己也跳过，导致容器里没有
 * {@code OrderEventPublisher} 类型的 Bean。
 */
@Component
@ConditionalOnProperty(name = "app.order-event.publisher", havingValue = "local", matchIfMissing = true)
public class LocalOrderEventPublisher implements OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalOrderEventPublisher.class);

    private final OrderEventConsumer consumer;

    public LocalOrderEventPublisher(OrderEventConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public void publish(OutboxEvent event) {
        log.info("派发订单事件: type={} orderNo={} eventId={}",
                event.getEventType(), event.getAggregateId(), event.getEventId());
        consumer.consume(event);
    }
}
