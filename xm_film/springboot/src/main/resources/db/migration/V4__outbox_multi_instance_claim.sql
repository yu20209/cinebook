-- ============================================================
-- V4__outbox_multi_instance_claim.sql
-- Outbox 多实例认领：新增 PROCESSING 状态、认领实例与租约时间
--
-- 背景：原来的派发任务用"查 PENDING → 逐条投递 → 标记 SENT"，
--       多实例同时运行时同一条事件会被重复投递；且投递过程中进程崩溃，
--       事件会一直停在 PENDING 被反复重发（虽然消费端幂等，但浪费资源）。
--
-- 改造后：
--   1. 认领：UPDATE ... SET status='PROCESSING', claimed_by=实例, claimed_until=租约
--      借助 SKIP LOCKED 让不同实例取到互不重叠的事件集合；
--   2. 租约：实例崩溃后 claimed_until 到期，事件可被其它实例重新认领；
--   3. 完结：只有 claimed_by 匹配的实例才能标记 SENT / 退回 PENDING / FAILED。
--
-- 幂等：全部操作带 information_schema 存在性判断，可重复执行。
-- ============================================================

-- 1. 认领实例标识
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'outbox_event' AND column_name = 'claimed_by'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `outbox_event` ADD COLUMN `claimed_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT ''认领实例标识（PROCESSING 时有值）'' AFTER `error_msg`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 认领租约到期时间
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'outbox_event' AND column_name = 'claimed_until'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `outbox_event` ADD COLUMN `claimed_until` datetime DEFAULT NULL COMMENT ''认领租约到期时间，过期后允许其它实例重新认领'' AFTER `claimed_by`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 认领扫描索引：按 (status, claimed_until) 过滤 PENDING 与超时租约
SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'outbox_event' AND index_name = 'idx_outbox_claim'
);
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `outbox_event` ADD KEY `idx_outbox_claim` (`status`, `claimed_until`)',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
