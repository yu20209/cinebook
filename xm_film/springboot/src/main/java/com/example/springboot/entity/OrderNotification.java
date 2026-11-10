package com.example.springboot.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * 订单事件通知：消费侧的真实业务落库，证明事件链路"消费到了、并且产生了业务结果"。
 *
 * <p>{@code eventId} 上有唯一索引，重复投递不会重复写通知 ——
 * 与 mq_consume_log 一起构成两级幂等：日志挡住重复消费，唯一键挡住重复落库。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderNotification implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer id;
    /** 来源事件编号（唯一） */
    private String eventId;
    /** ORDER_PAID / ORDER_CANCELLED / ORDER_REFUNDED */
    private String eventType;
    /** 订单号 */
    private String orderNo;
    /** 下单用户 ID（载荷缺失时为空） */
    private Integer userId;
    /** 通知正文 */
    private String content;
    private String createdAt;

    public static final int CONTENT_MAX_LENGTH = 255;
}
