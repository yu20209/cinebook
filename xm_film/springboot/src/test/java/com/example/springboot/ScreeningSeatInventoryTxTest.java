package com.example.springboot;

import com.example.springboot.mapper.ScreeningSeatMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 座位库存的事务与并发语义（任务 8）。
 *
 * <p>用独立测试库 xm_film_test + 负数场次 ID 隔离数据，结束后清理。
 */
@SpringBootTest
@ActiveProfiles("ci")
class ScreeningSeatInventoryTxTest {

    private static final int RECORD_ID = -99001;

    @Autowired
    ScreeningSeatMapper seatMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM screening_seat WHERE record_id = ?", RECORD_ID);
    }

    /** 事务回滚必须连带释放刚锁定的座位，否则座位会一直锁到超时补偿 */
    @Test
    void rollbackReleasesLockedSeat() {
        seatMapper.insertIgnoreBatch(RECORD_ID, List.of("1排1座"));
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            int locked = seatMapper.batchLockSeats(RECORD_ID, List.of("1排1座"), "TX-ROLLBACK", 5);
            assertThat(locked).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(seatStatus("1排1座")).isZero();
    }

    /** 并发锁同一座位：条件更新 + 行锁保证只有一个请求成功 */
    @Test
    void concurrentLockOnSameSeatOnlyOneWins() throws Exception {
        seatMapper.insertIgnoreBatch(RECORD_ID, List.of("1排1座"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> task = () -> {
                start.await();
                return new TransactionTemplate(transactionManager).execute(status ->
                        seatMapper.batchLockSeats(RECORD_ID, List.of("1排1座"),
                                "TX-" + UUID.randomUUID(), 5));
            };
            Future<Integer> first = pool.submit(task);
            Future<Integer> second = pool.submit(task);
            start.countDown();

            int total = first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS);
            assertThat(total).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private int seatStatus(String seatCode) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM screening_seat WHERE record_id = ? AND seat_code = ?",
                Integer.class, RECORD_ID, seatCode);
        return status == null ? -1 : status;
    }
}
