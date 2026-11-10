-- ============================================================
-- V1__init_seat_inventory.sql
-- 座位级库存 + 订单请求幂等 + Outbox + 消费幂等表（结构变更）
--
-- 执行方式：mysql -uroot -p <本次库名> < V1__init_seat_inventory.sql
-- 说明：脚本可重复执行（列/索引先查 information_schema 再加），
--       不对既有表做 DROP，避免误删线上数据。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 场次座位库存表
--    以 (record_id, seat_code) 唯一，用条件更新实现原子锁座
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `screening_seat` (
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

-- 清理孤儿座位（场次已删但库存行还在），否则下一步外键无法建立
DELETE ss FROM `screening_seat` ss
LEFT JOIN `record` r ON r.id = ss.record_id
WHERE r.id IS NULL;

-- 外键：删除场次时级联清理座位，避免批量删排片留下永远不可达的库存行
SET @fk_exists := (
  SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE table_schema = DATABASE() AND table_name = 'screening_seat'
    AND constraint_name = 'fk_screening_seat_record'
);
SET @ddl := IF(@fk_exists = 0,
  'ALTER TABLE `screening_seat` ADD CONSTRAINT `fk_screening_seat_record` FOREIGN KEY (`record_id`) REFERENCES `record` (`id`) ON DELETE CASCADE',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- CHECK：库存状态只允许 0/1/2，挡掉绕过条件更新直接写脏值的可能
SET @chk_exists := (
  SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE table_schema = DATABASE() AND table_name = 'screening_seat'
    AND constraint_name = 'chk_screening_seat_status'
);
SET @ddl := IF(@chk_exists = 0,
  'ALTER TABLE `screening_seat` ADD CONSTRAINT `chk_screening_seat_status` CHECK (`status` IN (0, 1, 2))',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
-- ------------------------------------------------------------
-- 2. 订单表新增 request_id 并建立幂等唯一约束
-- ------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'ordered' AND column_name = 'request_id'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `ordered` ADD COLUMN `request_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''下单幂等标识'' AFTER `pickup_code`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ordered' AND index_name = 'uk_ordered_user_request'
);
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `ordered` ADD UNIQUE KEY `uk_ordered_user_request` (`user_id`, `request_id`)',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. Outbox 事件表：与业务同事务落库，后台派发器投递
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `outbox_event` (
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
-- 4. MQ 消费幂等记录：UNIQUE(consumer_name, event_id)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mq_consume_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `consumer_name` varchar(32) NOT NULL COMMENT '消费者名称',
  `event_id` varchar(64) NOT NULL COMMENT '事件编号',
  `consumed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consumer_event` (`consumer_name`, `event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MQ 消费幂等记录';
