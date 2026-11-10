package com.example.springboot.task;

import com.example.springboot.mapper.ScreeningSeatMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 座位锁补偿任务：兜底清理已过期但仍在"已锁定"的座位。
 *
 * <p>正常路径下超时释放由订单清理任务完成；但进程崩溃、任务漏跑、或锁过期时间
 * 与订单状态短暂不一致时，座位会一直挂着"已锁定"，别人永远选不到。
 * 本任务直接按 lock_expire_at 扫表释放，是最后一道保险。
 *
 * <p>它只释放"锁定且已过期"的座位，不会碰已售出的座位，也不会碰仍在有效期内的锁。
 */
@Component
public class SeatLockCompensationTask {

    private static final Logger log = LoggerFactory.getLogger(SeatLockCompensationTask.class);

    private final ScreeningSeatMapper screeningSeatMapper;

    public SeatLockCompensationTask(ScreeningSeatMapper screeningSeatMapper) {
        this.screeningSeatMapper = screeningSeatMapper;
    }

    @Scheduled(fixedDelayString = "${app.seat.cleanup-interval-ms:60000}")
    public void cleanupExpiredLocks() {
        try {
            int released = screeningSeatMapper.cleanupExpiredLocks();
            if (released > 0) {
                log.info("座位锁补偿：释放过期锁定座位 {} 个", released);
            }
        } catch (Exception e) {
            log.error("座位锁补偿任务异常（下轮继续）: {}", e.getMessage());
        }
    }
}
