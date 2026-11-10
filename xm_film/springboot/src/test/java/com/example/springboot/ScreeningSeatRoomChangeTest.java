package com.example.springboot;

import com.example.springboot.entity.Record;
import com.example.springboot.exception.CustomException;
import com.example.springboot.mapper.ScreeningSeatMapper;
import com.example.springboot.service.RecordService;
import com.example.springboot.service.ScreeningSeatProvisioningService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 座位库存与场次变更的联动语义（验收矩阵 T10 / T18 / T19）。
 *
 * <p>T18：有订单或已占用座位时禁止换影厅，库存保持不变；
 * 无占用时换厅必须清空旧厅座位并按新厅重建。
 *
 * <p>T19：删除场次后 screening_seat 不得留下孤儿行（外键 ON DELETE CASCADE）。
 *
 * <p>T10：释放座位必须带订单号守卫，只释放本订单锁定的座位。
 */
@SpringBootTest
@ActiveProfiles("ci")
class ScreeningSeatRoomChangeTest {

    private static final String FUTURE_START = "2027-12-31 10:00:00";

    @Autowired
    RecordService recordService;

    @Autowired
    ScreeningSeatProvisioningService provisioningService;

    @Autowired
    ScreeningSeatMapper seatMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    private int cinemaId;
    private int roomAId;
    private int roomBId;
    private int filmId;
    private int recordId;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO cinema (username, password, name, status) "
                + "VALUES (?, 'x', 'seat-room-test', '已审核')", "seat-room-" + suffix);
        cinemaId = lastId();
        // A 厅 2x2=4 座，B 厅 3x3=9 座：换厅后座位数必须跟着变
        jdbcTemplate.update("INSERT INTO room (cinema_id, title, name, seat_rows, seat_cols) "
                + "VALUES (?, 'seat-room', 'A厅', 2, 2)", cinemaId);
        roomAId = lastId();
        jdbcTemplate.update("INSERT INTO room (cinema_id, title, name, seat_rows, seat_cols) "
                + "VALUES (?, 'seat-room', 'B厅', 3, 3)", cinemaId);
        roomBId = lastId();
        jdbcTemplate.update("INSERT INTO film (title, time, status) VALUES (?, 120, '已上映')",
                "seat-room-film-" + suffix);
        filmId = lastId();
        jdbcTemplate.update("INSERT INTO record (cinema_id, room_id, film_id, title, start, price, status) "
                        + "VALUES (?, ?, ?, ?, ?, 10.00, '正常')",
                cinemaId, roomAId, filmId, "seat-room-film-" + suffix, FUTURE_START);
        recordId = lastId();
        provisionSeats(roomAId);
    }

    @AfterEach
    void cleanup() {
        // 先删场次（级联清座位），再按外键依赖顺序回收父级
        deleteIfPresent("DELETE FROM record WHERE id = ?", recordId);
        deleteIfPresent("DELETE FROM room WHERE id = ?", roomAId);
        deleteIfPresent("DELETE FROM room WHERE id = ?", roomBId);
        deleteIfPresent("DELETE FROM film WHERE id = ?", filmId);
        deleteIfPresent("DELETE FROM cinema WHERE id = ?", cinemaId);
        cinemaId = roomAId = roomBId = filmId = recordId = 0;
    }

    /** T18：已有占用（座位被锁定）时换厅被拒绝，场次与库存都不变 */
    @Test
    void roomChangeRejectedWhenSeatOccupied() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThat(seatMapper.batchLockSeats(recordId, List.of("1排1座"), "ORDER-A", 5)).isEqualTo(1));

        Record change = new Record();
        change.setId(recordId);
        change.setRoomId(roomBId);

        assertThatThrownBy(() -> recordService.updateScoped(change, "ADMIN", null))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("无法更换影厅");

        assertThat(roomOfRecord()).isEqualTo(roomAId);
        assertThat(seatCount()).isEqualTo(4);
        assertThat(seatStatus("1排1座")).isEqualTo(1);
    }

    /** T18：无占用时换厅清空旧座位并按新厅行列重建 */
    @Test
    void roomChangeRebuildsSeatsWhenNoOccupancy() {
        Record change = new Record();
        change.setId(recordId);
        change.setRoomId(roomBId);

        recordService.updateScoped(change, "ADMIN", null);

        assertThat(roomOfRecord()).isEqualTo(roomBId);
        assertThat(seatCount()).isEqualTo(9);
        assertThat(seatCodes()).contains("3排3座");
    }

    /** T19：删除场次后库存行随之级联删除，不产生孤儿记录 */
    @Test
    void deletingRecordLeavesNoOrphanSeats() {
        recordService.deleteScoped(recordId, "ADMIN", null);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM record WHERE id = ?", Integer.class, recordId))
                .isZero();
        assertThat(seatCount()).isZero();
    }

    /** T10：唯一键冲突后的批量释放只影响本订单锁定的座位 */
    @Test
    void releaseOnlyReleasesSeatsLockedBySameOrder() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(seatMapper.batchLockSeats(recordId, List.of("1排1座"), "ORDER-A", 5)).isEqualTo(1);
            assertThat(seatMapper.batchLockSeats(recordId, List.of("1排2座"), "ORDER-B", 5)).isEqualTo(1);

            int released = seatMapper.batchReleaseSeats(recordId, List.of("1排1座", "1排2座"), "ORDER-A");
            assertThat(released).isEqualTo(1);
        });

        assertThat(seatStatus("1排1座")).isZero();
        assertThat(seatStatus("1排2座")).isEqualTo(1);
    }

    private void provisionSeats(int roomId) {
        Record target = new Record();
        target.setId(recordId);
        target.setRoomId(roomId);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> provisioningService.provision(target));
    }

    private int roomOfRecord() {
        Integer roomId = jdbcTemplate.queryForObject("SELECT room_id FROM record WHERE id = ?", Integer.class, recordId);
        return roomId == null ? -1 : roomId;
    }

    private int seatCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM screening_seat WHERE record_id = ?", Integer.class, recordId);
        return count == null ? -1 : count;
    }

    private List<String> seatCodes() {
        return jdbcTemplate.queryForList(
                "SELECT seat_code FROM screening_seat WHERE record_id = ? ORDER BY seat_code", String.class, recordId);
    }

    private int seatStatus(String seatCode) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM screening_seat WHERE record_id = ? AND seat_code = ?",
                Integer.class, recordId, seatCode);
        return status == null ? -1 : status;
    }

    private int lastId() {
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Integer.class);
    }

    private void deleteIfPresent(String sql, int id) {
        if (id > 0) {
            jdbcTemplate.update(sql, id);
        }
    }
}
