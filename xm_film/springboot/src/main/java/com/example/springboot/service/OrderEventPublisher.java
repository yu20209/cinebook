package com.example.springboot.service;

import com.example.springboot.entity.OutboxEvent;

/**
 * 订单事件发布器。
 *
 * <p>抽成接口是为了让投递方式可替换：
 * 本地/测试用 {@link LocalOrderEventPublisher} 直接交给消费者；
 * 生产可以接 RabbitMQ / Kafka，只需换一个实现，Outbox 的可靠性语义不变。
 */
public interface OrderEventPublisher {
    void publish(OutboxEvent event);
}
