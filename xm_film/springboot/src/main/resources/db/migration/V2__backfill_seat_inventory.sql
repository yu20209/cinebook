-- ============================================================
-- V2__backfill_seat_inventory.sql
-- 历史数据迁移：为既有场次生成座位行，并把仍有效的订单占用同步到座位库存
--
-- 执行方式：mysql -uroot -p <本次库名> < V2__backfill_seat_inventory.sql
--
-- 幂等性：座位写入用 INSERT IGNORE，状态回填只"向上"推进（可用→锁定→售出），
--         重复执行结果一致，可用于补齐后来新建的场次。
--
-- 重要原则（上一版脚本的缺陷）：
--   已取消 / 已退票的订单**不参与**占用回填。
--   历史脚本最后一步会把取消/退票订单匹配的座位改回"可用"，
--   若该座位后来被订单 B 买走，这一步会把 B 已售出的座位错误清空 → 重复售票。
--   因此本脚本只处理"当前仍有效"的订单：
--     · 未超时的待支付订单 → 锁定(status=1)
--     · 待取票 / 已取票订单 → 已售出(status=2)
--   其余座位保持默认的可用状态，不做任何反向清除。
--
-- 建议：迁移前备份数据库，并在副本库先执行一次。
-- ============================================================

-- 统一连接排序规则：库表使用 utf8mb4_unicode_ci，若客户端默认 0900_ai_ci
-- 会在 FIND_IN_SET / 状态字符串比较处报 "Illegal mix of collations"
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

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
-- 2. 冲突预检：同一场次同一座位若存在多个有效订单，必须中止迁移
--    用带 CHECK 约束的临时表做守卫：计数非 0 时 INSERT 直接失败，
--    脚本随之中断，避免静默覆盖出错误库存。
--    中止后按下面这条查询人工核对冲突明细并清理数据，再重新执行本脚本：
--      SELECT o.record_id, ss.seat_code, GROUP_CONCAT(o.orders) AS orders
--      FROM screening_seat ss
--      JOIN ordered o ON o.record_id = ss.record_id
--       AND o.status IN ('待支付', '待取票', '已取票')
--       AND (o.status <> '待支付' OR o.pending_timeout_at IS NULL OR o.pending_timeout_at > NOW())
--       AND FIND_IN_SET(ss.seat_code COLLATE utf8mb4_unicode_ci,
--                       REPLACE(o.seat, '，', ',') COLLATE utf8mb4_unicode_ci)
--      GROUP BY o.record_id, ss.seat_code
--      HAVING COUNT(DISTINCT o.orders) > 1;
-- ------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS `tmp_v2_conflict_guard`;
CREATE TEMPORARY TABLE `tmp_v2_conflict_guard` (
  `conflict_seats` int NOT NULL,
  CONSTRAINT `chk_v2_no_conflict` CHECK (`conflict_seats` = 0)
);

INSERT INTO `tmp_v2_conflict_guard` (`conflict_seats`)
SELECT COUNT(*)
FROM (
  SELECT ss.record_id, ss.seat_code
  FROM `screening_seat` ss
  JOIN `ordered` o ON o.record_id = ss.record_id
   AND o.status IN ('待支付', '待取票', '已取票')
   -- 待支付订单必须未超时：超时订单不占用库存
   AND (o.status <> '待支付'
        OR o.pending_timeout_at IS NULL
        OR o.pending_timeout_at > NOW())
   AND FIND_IN_SET(ss.seat_code COLLATE utf8mb4_unicode_ci,
                   REPLACE(o.seat, '，', ',') COLLATE utf8mb4_unicode_ci)
  GROUP BY ss.record_id, ss.seat_code
  HAVING COUNT(DISTINCT o.orders) > 1
) conflicts;

DROP TEMPORARY TABLE IF EXISTS `tmp_v2_conflict_guard`;

-- ------------------------------------------------------------
-- 3. 未超时的待支付订单：座位标为已锁定，过期时间沿用订单的超时时间
--    已超时的待支付订单不回填 —— 它们不占用库存，等清理任务将其置为已取消
-- ------------------------------------------------------------
UPDATE `screening_seat` ss
JOIN `ordered` o ON o.record_id = ss.record_id
SET ss.status = 1,
    ss.lock_order_no = o.orders,
    ss.lock_expire_at = COALESCE(o.pending_timeout_at, NOW() + INTERVAL 5 MINUTE)
WHERE o.status = '待支付'
  AND (o.pending_timeout_at IS NULL OR o.pending_timeout_at > NOW())
  AND ss.status = 0
  AND FIND_IN_SET(ss.seat_code COLLATE utf8mb4_unicode_ci,
                  REPLACE(o.seat, '，', ',') COLLATE utf8mb4_unicode_ci);

-- ------------------------------------------------------------
-- 4. 待取票 / 已取票订单：座位标为已售出
--    只从"未售出"推进到"已售出"，不会把已取消订单的座位反向清空
-- ------------------------------------------------------------
UPDATE `screening_seat` ss
JOIN `ordered` o ON o.record_id = ss.record_id
SET ss.status = 2,
    ss.lock_order_no = NULL,
    ss.lock_expire_at = NULL
WHERE o.status IN ('待取票', '已取票')
  AND ss.status IN (0, 1)
  AND FIND_IN_SET(ss.seat_code COLLATE utf8mb4_unicode_ci,
                  REPLACE(o.seat, '，', ',') COLLATE utf8mb4_unicode_ci);

-- ------------------------------------------------------------
-- 5. 对账查询（人工核查用，可注释）
-- ------------------------------------------------------------
-- SELECT ss.status, COUNT(*) FROM screening_seat ss GROUP BY ss.status;
-- 期望：每个场次的座位数 = 影厅行列数
-- SELECT r.id, COUNT(ss.id) AS seats, COALESCE(NULLIF(rm.seat_rows,0),8)*COALESCE(NULLIF(rm.seat_cols,0),8) AS expected
--   FROM record r JOIN room rm ON rm.id = r.room_id
--   LEFT JOIN screening_seat ss ON ss.record_id = r.id
--  GROUP BY r.id, rm.seat_rows, rm.seat_cols;
