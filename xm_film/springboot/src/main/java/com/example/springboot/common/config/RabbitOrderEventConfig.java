package com.example.springboot.common.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明：只有一个业务交换机 + 一个业务队列 + 一套死信（DLX/DLQ）。
 *
 * <p>只在 {@code app.order-event.publisher=rabbit} 时生效 ——
 * 本机没有 broker 时仍以 local 模式启动，不会因为缺少 MQ 而启动失败。
 *
 * <p>队列通过 {@code x-dead-letter-exchange} 绑定死信交换机：
 * 消费端重试耗尽后 basicNack(requeue=false)，消息自动进入死信队列留待排查，
 * 而不是重新入队造成无限循环。
 */
@Configuration
@ConditionalOnProperty(name = "app.order-event.publisher", havingValue = "rabbit")
public class RabbitOrderEventConfig {

    @Bean
    public TopicExchange orderEventExchange(
            @Value("${app.order-event.exchange:cinebook.order.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Queue orderEventQueue(
            @Value("${app.order-event.queue:cinebook.order.queue}") String queue,
            @Value("${app.order-event.dead-letter-exchange:cinebook.order.dlx}") String dlx,
            @Value("${app.order-event.dead-letter-routing-key:cinebook.order.dead}") String dlRoutingKey) {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(dlx)
                .deadLetterRoutingKey(dlRoutingKey)
                .build();
    }

    @Bean
    public Binding orderEventBinding(
            Queue orderEventQueue,
            TopicExchange orderEventExchange,
            @Value("${app.order-event.routing-key:cinebook.order.created}") String routingKey) {
        return BindingBuilder.bind(orderEventQueue).to(orderEventExchange).with(routingKey);
    }

    @Bean
    public DirectExchange orderEventDeadLetterExchange(
            @Value("${app.order-event.dead-letter-exchange:cinebook.order.dlx}") String dlx) {
        return new DirectExchange(dlx, true, false);
    }

    @Bean
    public Queue orderEventDeadLetterQueue(
            @Value("${app.order-event.dead-letter-queue:cinebook.order.dlq}") String dlq) {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    public Binding orderEventDeadLetterBinding(
            Queue orderEventDeadLetterQueue,
            DirectExchange orderEventDeadLetterExchange,
            @Value("${app.order-event.dead-letter-routing-key:cinebook.order.dead}") String dlRoutingKey) {
        return BindingBuilder.bind(orderEventDeadLetterQueue).to(orderEventDeadLetterExchange).with(dlRoutingKey);
    }
}
