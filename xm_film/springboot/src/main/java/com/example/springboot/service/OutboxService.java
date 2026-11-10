package com.example.springboot.service;

import com.example.springboot.entity.Ordered;
import com.example.springboot.entity.OutboxEvent;
import com.example.springboot.mapper.OutboxEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Outbox 服务：可靠事件写入与派发。
 *
 * <p>写入侧 {@link #record} 用 {@code Propagation.MANDATORY} 以外的默认传播，
 * 但**必须由业务事务调用**（支付/取消/退票），这样事件与业务数据同生共死：
 * 业务回滚，事件一起回滚；业务提交，事件必然落库。
 *
 * <p>派发侧 {@link #dispatchPending} 由定时任务驱动，失败按指数退避重试，
 * 超过上限标记 FAILED 并保留现场，便于人工介入 —— 绝不静默丢事件。
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    /** 单次派发批量上限，避免一次扫描过多拖长任务 */
    private static final int BATCH_LIMIT = 100;
    /** 最大重试次数：超过后转 FAILED，等待人工处理 */
    private static final int MAX_RETRY = 8;

    private final OutboxEventMapper outboxEventMapper;
    private final OrderEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventMapper outboxEventMapper,
                         OrderEventPublisher publisher,
                         ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    /**
     * 在业务事务内记录一条事件。调用方必须处在事务中，否则会失去原子性。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String eventType, Ordered ordered) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString().replace("-", ""));
        event.setEventType(eventType);
        event.setAggregateId(ordered.getOrders());
        event.setStatus(OutboxEvent.STATUS_PENDING);
        try {
            event.setPayload(objectMapper.writeValueAsString(ordered));
        } catch (Exception e) {
            // 序列化失败不应让整笔支付失败：降级为最小载荷，事件仍然可派发
            log.warn("订单事件序列化失败，降级为最小载荷 orderNo={}: {}", ordered.getOrders(), e.getMessage());
            event.setPayload("{\"orders\":\"" + ordered.getOrders() + "\",\"status\":\"" + ordered.getStatus() + "\"}");
        }
        outboxEventMapper.insert(event);
    }

    /**
     * 派发一批待发送事件。返回本次成功投递的数量。
     */
    public int dispatchPending() {
        List<OutboxEvent> pending = outboxEventMapper.selectPendingToDispatch(BATCH_LIMIT);
        int sent = 0;
        for (OutboxEvent event : pending) {
            try {
                publisher.publish(event);
                outboxEventMapper.markSent(event.getId());
                sent++;
            } catch (Exception e) {
                int retry = event.getRetryCount() == null ? 0 : event.getRetryCount();
                String msg = truncate(e.getMessage());
                if (retry >= MAX_RETRY) {
                    outboxEventMapper.markDead(event.getId(), msg);
                    log.error("Outbox 事件重试超限转 FAILED，需人工介入: eventId={} type={}",
                            event.getEventId(), event.getEventType());
                } else {
                    // 指数退避：10s、30s、90s、270s… 避免故障期间打爆下游
                    int delay = (int) Math.min(600, 10 * Math.pow(3, retry));
                    outboxEventMapper.markFailed(event.getId(), msg, delay);
                }
            }
        }
        return sent;
    }

    private String truncate(String msg) {
        if (msg == null) {
            return "unknown";
        }
        return msg.length() > 480 ? msg.substring(0, 480) : msg;
    }
}
