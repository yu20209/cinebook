package com.example.springboot.service;

import com.example.springboot.entity.Record;
import com.example.springboot.entity.Room;
import com.example.springboot.mapper.RoomMapper;
import com.example.springboot.mapper.ScreeningSeatMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 新场次座位库存初始化。
 *
 * <p>场次落库后立刻按影厅的行列数生成 {@code screening_seat} 行，且与场次插入在同一个事务里：
 * 要么"场次 + 全部座位"一起成功，要么一起回滚，不会留下一个无法售卖的无座位场次。
 *
 * <p>历史场次由 {@code db/migration/V2__backfill_seat_inventory.sql} 补齐。
 */
@Service
public class ScreeningSeatProvisioningService {

    /** 影厅未配置行列数时的兜底（与 V2 迁移脚本、前端默认选座图一致） */
    static final int DEFAULT_ROWS = 8;

    static final int DEFAULT_COLS = 8;

    /** 上限与 SeatParser 的座位号形状校验保持一致，避免生成永远卖不出去的座位 */
    static final int MAX_ROWS = 20;

    static final int MAX_COLS = 20;

    /** 单条 INSERT 的座位数上限，避免 SQL 包体过大 */
    private static final int BATCH_SIZE = 500;

    private final ScreeningSeatMapper screeningSeatMapper;

    private final RoomMapper roomMapper;

    public ScreeningSeatProvisioningService(ScreeningSeatMapper screeningSeatMapper, RoomMapper roomMapper) {
        this.screeningSeatMapper = screeningSeatMapper;
        this.roomMapper = roomMapper;
    }

    /**
     * 为场次生成座位行，重复调用安全（INSERT IGNORE 靠 uk_record_seat 去重）。
     *
     * <p>{@link Propagation#MANDATORY}：必须在调用方事务内执行，
     * 否则场次插入与座位初始化会分属两个事务，回滚时留下孤儿场次。
     *
     * @return 实际新插入的座位数（已存在的座位不计）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public int provision(Record record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        Room room = record.getRoomId() == null ? null : roomMapper.selectById(record.getRoomId());
        int rows = clamp(room == null ? null : room.getSeatRows(), DEFAULT_ROWS, MAX_ROWS);
        int cols = clamp(room == null ? null : room.getSeatCols(), DEFAULT_COLS, MAX_COLS);

        List<String> codes = new ArrayList<>(rows * cols);
        for (int row = 1; row <= rows; row++) {
            for (int col = 1; col <= cols; col++) {
                codes.add(row + "排" + col + "座");
            }
        }
        int inserted = 0;
        for (int from = 0; from < codes.size(); from += BATCH_SIZE) {
            List<String> chunk = codes.subList(from, Math.min(from + BATCH_SIZE, codes.size()));
            inserted += screeningSeatMapper.insertIgnoreBatch(record.getId(), chunk);
        }
        return inserted;
    }

    /** 行列数归一：空值/非正数用默认值，超过上限按上限截断，防止一次生成海量座位 */
    private static int clamp(Integer value, int defaultValue, int max) {
        if (value == null || value <= 0) {
            return defaultValue;
        }
        return Math.min(value, max);
    }
}
