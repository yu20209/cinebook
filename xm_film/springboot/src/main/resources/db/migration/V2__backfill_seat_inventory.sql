-- ============================================================
-- V2__backfill_seat_inventory.sql
-- 历史数据迁移：为既有场次生成座位行，并把订单状态同步到座位库存
--
-- 执行方式：mysql -uroot -p <本次库名> < V2__backfill_seat_inventory.sql
-- 幂等性：座位写入用 INSERT IGNORE，订单状态回填用带状态守卫的 UPDATE，
--         重复执行结果一致，可用于补齐后来新建的场次。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 按影厅行列数生成座位（上限 20×20，超出部分不生成）
--    行列上限 20 与 SeatParser 的座位号形状校验保持一致
-- ------------------------------------------------------------
INSERT IGNORE INTO `screening_seat` (`record_id`, `seat_code`, `status`)
WITH RECURSIVE
  row_nums AS (
    SELECT 1 AS rw
    UNION ALL SELECT rw + 1 FROM row_nums WHERE rw < 20
  ),
  col_nums AS (
    SELECT 1 AS col
    UNION ALL SELECT col + 1 FROM col_nums WHERE col < 20
  )
SELECT r.id,
       CONCAT(rw, '排', col, '座'),
       0
FROM `record` r
JOIN `room` rm ON rm.id = r.room_id
JOIN row_nums ON rw <= COALESCE(NULLIF(rm.seat_rows, 0), 8)
JOIN col_nums ON col <= COALESCE(NULLIF(rm.seat_cols, 0), 8)
ORDER BY r.id, rw, col;

-- ------------------------------------------------------------
-- 2. 待支付订单：座位标为已锁定，过期时间沿用订单的超时时间
-- ------------------------------------------------------------
UPDATE `screening_seat` ss
JOIN `ordered` o ON o.record_id = ss.record_id
SET ss.status = 1,
    ss.lock_order_no = o.orders,
    ss.lock_expire_at = COALESCE(o.pending_timeout_at, NOW() + INTERVAL 5 MINUTE)
WHERE o.status = '待支付'
  AND ss.status = 0
  AND FIND_IN_SET(ss.seat_code, REPLACE(o.seat, '，', ','));

-- ------------------------------------------------------------
-- 3. 已支付/已取票订单：座位标为已售出
-- ------------------------------------------------------------
UPDATE `screening_seat` ss
JOIN `ordered` o ON o.record_id = ss.record_id
SET ss.status = 2,
    ss.lock_order_no = NULL,
    ss.lock_expire_at = NULL
WHERE o.status IN ('待取票', '已取票')
  AND ss.status IN (0, 1)
  AND FIND_IN_SET(ss.seat_code, REPLACE(o.seat, '，', ','));

-- ------------------------------------------------------------
-- 4. 已取消/已退票订单：座位恢复可用（不能覆盖后来订单的占用，故带状态范围）
-- ------------------------------------------------------------
UPDATE `screening_seat` ss
JOIN `ordered` o ON o.record_id = ss.record_id
SET ss.status = 0,
    ss.lock_order_no = NULL,
    ss.lock_expire_at = NULL
WHERE o.status IN ('已取消', '已退票')
  AND ss.status IN (1, 2)
  AND FIND_IN_SET(ss.seat_code, REPLACE(o.seat, '，', ','));

-- ------------------------------------------------------------
-- 5. 对账查询（人工核查用，可注释）
-- ------------------------------------------------------------
-- SELECT ss.status, COUNT(*) FROM screening_seat ss GROUP BY ss.status;
-- SELECT COUNT(*) AS expected FROM record r JOIN room rm ON rm.id = r.room_id;
-- 期望：SELECT SUM(COALESCE(rm.seat_rows,8)*COALESCE(rm.seat_cols,8))
--        FROM record r JOIN room rm ON rm.id = r.room_id; 等于 screening_seat 行数
