package com.example.springboot.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Outbox 事件：与业务数据在同一个本地事务里写入，
 * 由后台派发器异步投递（本地直派或 MQ），投递成功后才标记 SENT。
 *
 * <p>它解决的是"数据库提交成功、消息没发出去"的经典双写不一致：
 * 只要业务事务提交了，事件就一定在表里，派发失败可以无限重试，直到成功。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer id;
    /** 全局唯一事件编号（UUID），消费端据此幂等 */
    private String eventId;
    /** ORDER_PAID / ORDER_CANCELLED / ORDER_REFUNDED */
    private String eventType;
    /** 聚合根 ID：这里就是订单号 */
    private String aggregateId;
    /** 事件载荷（JSON 快照） */
    private String payload;
    /** PENDING / SENT / FAILED */
    private String status;
    private Integer retryCount;
    private String nextRetryAt;
    private String errorMsg;
    private String createdAt;
    private String updatedAt;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    public static final String TYPE_ORDER_PAID = "ORDER_PAID";
    public static final String TYPE_ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String TYPE_ORDER_REFUNDED = "ORDER_REFUNDED";
}
