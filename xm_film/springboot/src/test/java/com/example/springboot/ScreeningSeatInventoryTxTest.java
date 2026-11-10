package com.example.springboot;

import com.example.springboot.mapper.ScreeningSeatMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    /**
     * screening_seat 对 record 有 ON DELETE CASCADE 外键，
     * 因此必须先把父级（影院 / 影厅 / 影片 / 场次）准备好，
     * 否则负数 ID 的座位行会被外键拒绝（INSERT IGNORE 静默跳过）。
     */
    @BeforeEach
    void seedRecord() {
        cleanup();
        jdbcTemplate.update("INSERT INTO cinema (id, username, password, name, status) "
                + "VALUES (?, ?, 'x', 'seat-tx-cinema', '已审核')", RECORD_ID, "seat-tx-cinema");
        jdbcTemplate.update("INSERT INTO room (id, cinema_id, title, name) VALUES (?, ?, 'seat-tx-room', 'seat-tx-room')",
                RECORD_ID, RECORD_ID);
        jdbcTemplate.update("INSERT INTO film (id, title, status) VALUES (?, 'seat-tx-film', '已上映')", RECORD_ID);
        jdbcTemplate.update("INSERT INTO record (id, cinema_id, room_id, film_id, title, start, price, status) "
                + "VALUES (?, ?, ?, ?, 'seat-tx-film', '2026-12-31 10:00:00', 10.00, '正常')",
                RECORD_ID, RECORD_ID, RECORD_ID, RECORD_ID);
    }

    @AfterEach
    void cleanup() {
        // 先删场次（级联清座位），再按外键依赖顺序清理父级
        jdbcTemplate.update("DELETE FROM record WHERE id = ?", RECORD_ID);
        jdbcTemplate.update("DELETE FROM room WHERE id = ?", RECORD_ID);
        jdbcTemplate.update("DELETE FROM film WHERE id = ?", RECORD_ID);
        jdbcTemplate.update("DELETE FROM cinema WHERE id = ?", RECORD_ID);
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
