-- ============================================================
-- V3__order_notification.sql
-- Outbox 消费侧业务表：订单事件通知
--
-- 事件消费成功后写入一条通知记录（支付成功 / 订单取消 / 退票完成），
-- (event_id) 唯一，天然幂等；重复投递不会产生重复通知。
-- ============================================================

CREATE TABLE IF NOT EXISTS `order_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) NOT NULL COMMENT '来源事件编号，唯一',
  `event_type` varchar(32) NOT NULL COMMENT 'ORDER_PAID / ORDER_CANCELLED / ORDER_REFUNDED',
  `order_no` varchar(64) NOT NULL COMMENT '订单号',
  `user_id` int DEFAULT NULL COMMENT '下单用户ID',
  `content` varchar(255) NOT NULL COMMENT '通知内容',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_event` (`event_id`),
  KEY `idx_notification_user` (`user_id`),
  KEY `idx_notification_order` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件通知（消费侧业务落库）';
