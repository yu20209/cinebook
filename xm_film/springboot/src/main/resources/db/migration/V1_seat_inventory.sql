-- ============================================================
-- CineBook 改造迁移脚本 V1：座位库存重构 + 请求幂等 + Outbox
-- 在 xm-film 库执行
-- ============================================================

USE `xm-film`;

-- ------------------------------------------------------------
-- 1. 场次座位库存表（座位级锁）
--    替代原方案：从 ordered.seat 逗号分隔字符串 + 整场 record 行锁
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `screening_seat`;
CREATE TABLE `screening_seat` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `record_id` int NOT NULL COMMENT '场次ID',
  `seat_code` varchar(16) NOT NULL COMMENT '座位编号，如 1排1座',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0 可用、1 已锁定、2 已售出',
  `lock_order_no` varchar(64) DEFAULT NULL COMMENT '当前锁定该座位的订单号（status=1 时有值）',
  `lock_expire_at` datetime DEFAULT NULL COMMENT '锁定过期时间（status=1 时有值）',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_record_seat` (`record_id`, `seat_code`),
  KEY `idx_seat_status` (`status`),
  KEY `idx_seat_lock_order` (`lock_order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='场次座位库存表（座位级锁）';

-- ------------------------------------------------------------
-- 2. 订单表新增 request_id + 幂等唯一约束
-- ------------------------------------------------------------
ALTER TABLE `ordered`
  ADD COLUMN `request_id` varchar(64) DEFAULT NULL COMMENT '请求幂等标识（客户端生成，同 user_id+request_id 唯一）' AFTER `orders`,
  ADD UNIQUE KEY `uk_user_request` (`user_id`, `request_id`);

-- ------------------------------------------------------------
-- 3. Outbox 事件表（事务内记录，异步发布到 MQ）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `outbox_event`;
CREATE TABLE `outbox_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) NOT NULL COMMENT '全局唯一事件编号（UUID）',
  `event_type` varchar(32) NOT NULL COMMENT 'ORDER_PAID / ORDER_CANCELLED / ORDER_REFUNDED',
  `aggregate_id` varchar(64) NOT NULL COMMENT '订单号',
  `payload` json DEFAULT NULL COMMENT '完整订单快照',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / FAILED',
  `retry_count` int NOT NULL DEFAULT '0',
  `next_retry_at` datetime DEFAULT NULL,
  `error_msg` varchar(500) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_id` (`event_id`),
  KEY `idx_outbox_status_next` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Outbox 事件表';

-- ------------------------------------------------------------
-- 4. MQ 消费幂等记录表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `mq_consume_log`;
CREATE TABLE `mq_consume_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `consumer_name` varchar(32) NOT NULL COMMENT '消费者名称',
  `event_id` varchar(64) NOT NULL COMMENT '事件编号',
  `consumed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consumer_event` (`consumer_name`, `event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MQ 消费幂等记录';

-- ============================================================
-- 迁移数据：根据 room.seat_rows/seat_cols 为每个 record 初始化座位
-- 并根据历史有效订单同步已占用座位
-- ============================================================

-- 4.1 为每个场次初始化座位（根据影厅行列数）
INSERT INTO screening_seat (record_id, seat_code, status)
SELECT r.id,
       CONCAT(rw, '排', col, '座'),
       0
FROM record r
JOIN room rm ON rm.id = r.room_id
JOIN (
  SELECT 1 AS rw UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
  UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8
  UNION SELECT 9 UNION SELECT 10
) row_nums ON row_nums.rw <= COALESCE(rm.seat_rows, 8)
JOIN (
  SELECT 1 AS col UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
  UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8
  UNION SELECT 9 UNION SELECT 10
) col_nums ON col_nums.col <= COALESCE(rm.seat_cols, 8)
ORDER BY r.id, rw, col
ON DUPLICATE KEY UPDATE seat_code = VALUES(seat_code);

-- 4.2 将历史待支付订单的座位标记为已锁定（lock_order_no + 5分钟过期）
UPDATE screening_seat ss
JOIN ordered o ON o.record_id = ss.record_id
SET ss.status = 1,
    ss.lock_order_no = o.orders,
    ss.lock_expire_at = COALESCE(o.pending_timeout_at, NOW() + INTERVAL 5 MINUTE)
WHERE o.status = '待支付'
  AND FIND_IN_SET(ss.seat_code, REPLACE(o.seat, '，', ','));

-- 4.3 将历史已支付/已取票订单的座位标记为已售出
UPDATE screening_seat ss
JOIN ordered o ON o.record_id = ss.record_id
SET ss.status = 2,
    ss.lock_order_no = NULL,
    ss.lock_expire_at = NULL
WHERE o.status IN ('待取票', '已取票')
  AND FIND_IN_SET(ss.seat_code, REPLACE(o.seat, '，', ','));

-- 4.4 将已取消/已退票订单的座位恢复为可用
UPDATE screening_seat ss
JOIN ordered o ON o.record_id = ss.record_id
SET ss.status = 0,
    ss.lock_order_no = NULL,
    ss.lock_expire_at = NULL
WHERE o.status IN ('已取消', '已退票')
  AND ss.status IN (1, 2)
  AND FIND_IN_SET(ss.seat_code, REPLACE(o.seat, '，', ','));

-- 4.5 迁移对账：验证座位总数与订单匹配
-- SELECT ss.status, COUNT(*) FROM screening_seat ss GROUP BY ss.status;
-- SELECT o.status, COUNT(*) FROM ordered o GROUP BY o.status;

SELECT '迁移完成' AS result;
