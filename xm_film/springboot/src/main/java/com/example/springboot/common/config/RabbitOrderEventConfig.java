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

    /** 业务交换机：Topic 类型，durable=true 保证 broker 重启后拓扑仍在 */
    @Bean
    public TopicExchange orderEventExchange(
            @Value("${app.order-event.exchange:cinebook.order.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    /**
     * 业务队列：持久化 + 绑定死信交换机。
     * 消费重试耗尽后 basicNack(requeue=false) 的消息会按这里配置的 DLX/路由键进入死信队列。
     */
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

    /**
     * 业务绑定：交换机按 routingKey 路由到业务队列。
     * mandatory 模式下若没有这条绑定，消息会被 broker 退回，Outbox 判定投递失败并重试。
     */
    @Bean
    public Binding orderEventBinding(
            Queue orderEventQueue,
            TopicExchange orderEventExchange,
            @Value("${app.order-event.routing-key:cinebook.order.created}") String routingKey) {
        return BindingBuilder.bind(orderEventQueue).to(orderEventExchange).with(routingKey);
    }

    /** 死信交换机：接收被拒绝/过期的消息，与业务交换机隔离 */
    @Bean
    public DirectExchange orderEventDeadLetterExchange(
            @Value("${app.order-event.dead-letter-exchange:cinebook.order.dlx}") String dlx) {
        return new DirectExchange(dlx, true, false);
    }

    /** 死信队列：保留失败消息现场，供人工排查与补偿，不设置过期时间 */
    @Bean
    public Queue orderEventDeadLetterQueue(
            @Value("${app.order-event.dead-letter-queue:cinebook.order.dlq}") String dlq) {
        return QueueBuilder.durable(dlq).build();
    }

    /** 死信绑定：按固定的 dead routing key 把死信送进死信队列 */
    @Bean
    public Binding orderEventDeadLetterBinding(
            Queue orderEventDeadLetterQueue,
            DirectExchange orderEventDeadLetterExchange,
            @Value("${app.order-event.dead-letter-routing-key:cinebook.order.dead}") String dlRoutingKey) {
        return BindingBuilder.bind(orderEventDeadLetterQueue).to(orderEventDeadLetterExchange).with(dlRoutingKey);
    }
}
