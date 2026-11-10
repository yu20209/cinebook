package com.example.springboot.task;

import com.example.springboot.service.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 派发任务：周期性把 PENDING 事件投递出去。
 *
 * <p>用 fixedDelay 而非 fixedRate：上一轮没跑完不叠加触发，
 * 避免下游故障时任务堆叠。失败重试与退避在 OutboxService 内部处理。
 */
@Component
public class OutboxDispatchTask {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchTask.class);

    private final OutboxService outboxService;

    public OutboxDispatchTask(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Scheduled(fixedDelayString = "${app.outbox.dispatch-interval-ms:3000}")
    public void dispatch() {
        try {
            int sent = outboxService.dispatchPending();
            if (sent > 0) {
                log.info("Outbox 本轮派发成功 {} 条", sent);
            }
        } catch (Exception e) {
            log.error("Outbox 派发任务异常（下轮继续）: {}", e.getMessage());
        }
    }
}
