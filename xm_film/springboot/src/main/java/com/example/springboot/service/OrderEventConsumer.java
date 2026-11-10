package com.example.springboot.service;

import com.example.springboot.entity.OutboxEvent;
import com.example.springboot.mapper.MqConsumeLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单事件消费者（幂等）。
 *
 * <p>投递语义是"至少一次"，因此消费者必须自己保证重复事件只生效一次。
 * 这里用 mq_consume_log 的唯一键做去重：同一 eventId 第二次进来时
 * insertIgnore 影响 0 行，直接跳过后续副作用。
 *
 * <p>后续要做的下游动作（发短信、写统计、通知影院）都加在这一步之后，
 * 它们天然继承了幂等保护。
 */
@Service
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    private static final String CONSUMER_NAME = "order-event";

    private final MqConsumeLogMapper consumeLogMapper;

    public OrderEventConsumer(MqConsumeLogMapper consumeLogMapper) {
        this.consumeLogMapper = consumeLogMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void consume(OutboxEvent event) {
        int inserted = consumeLogMapper.insertIgnore(CONSUMER_NAME, event.getEventId());
        if (inserted == 0) {
            log.info("重复事件已忽略: eventId={}", event.getEventId());
            return;
        }
        log.info("消费订单事件成功: type={} orderNo={}", event.getEventType(), event.getAggregateId());
        // 幂等点之后的下游副作用（通知、统计、对账）在此追加
    }
}
