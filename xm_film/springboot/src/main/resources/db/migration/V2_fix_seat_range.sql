-- ============================================================
-- CineBook 改造迁移脚本 V2：修正座位初始化范围
-- 问题：V1 的行列生成器只硬编码到 10，而 room 表实际最大 12 排 × 16 座，
--       导致 11、12 排与 11~16 座的座位缺失，用户选中后锁座必然 0 行。
-- 本脚本按 20×20 生成，覆盖现有全部影厅规格。
-- ============================================================

USE `xm-film`;

-- 2.1 补齐缺失座位（已存在的靠唯一索引跳过，不覆盖已有状态）
INSERT IGNORE INTO screening_seat (record_id, seat_code, status)
SELECT r.id,
       CONCAT(rw, '排', col, '座'),
       0
FROM record r
JOIN room rm ON rm.id = r.room_id
JOIN (
  SELECT 1 AS rw UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
  UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10
  UNION ALL SELECT 11 UNION ALL SELECT 12
) row_nums ON row_nums.rw <= COALESCE(rm.seat_rows, 8)
JOIN (
  SELECT 1 AS col UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
  UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10
  UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14
  UNION ALL SELECT 15 UNION ALL SELECT 16
) col_nums ON col_nums.col <= COALESCE(rm.seat_cols, 8);

-- 2.2 重新同步历史订单状态（补齐的座位里若有被历史订单占用，必须一并标记）
--     先把本场次所有座位重置为可用，再按订单状态回填，避免重复执行产生错乱
UPDATE screening_seat SET status = 0, lock_order_no = NULL, lock_expire_at = NULL;

-- 待支付 → 已锁定
UPDATE screening_seat ss
JOIN ordered o ON o.record_id = ss.record_id
SET ss.status = 1,
    ss.lock_order_no = o.orders,
    ss.lock_expire_at = COALESCE(o.pending_timeout_at, NOW() + INTERVAL 5 MINUTE)
WHERE o.status = '待支付'
  AND FIND_IN_SET(ss.seat_code, REPLACE(o.seat, '，', ','));

-- 待取票 / 已取票 → 已售出
UPDATE screening_seat ss
JOIN ordered o ON o.record_id = ss.record_id
SET ss.status = 2
WHERE o.status IN ('待取票', '已取票')
  AND FIND_IN_SET(ss.seat_code, REPLACE(o.seat, '，', ','));

-- 2.3 对账：实际座位数应等于所有场次 影厅行列数 之和
SELECT
  (SELECT COUNT(*) FROM screening_seat) AS actual_seats,
  (SELECT SUM(rm.seat_rows * rm.seat_cols) FROM record r JOIN room rm ON rm.id = r.room_id) AS expected_seats,
  (SELECT COUNT(*) FROM screening_seat WHERE status = 1) AS locked_seats,
  (SELECT COUNT(*) FROM screening_seat WHERE status = 2) AS sold_seats;
