package com.example.springboot.mapper;

import com.example.springboot.entity.OrderNotification;
import org.apache.ibatis.annotations.Param;

/**
 * 订单事件通知 Mapper。
 */
public interface OrderNotificationMapper {

    /**
     * 写入通知：依赖 uk_notification_event(event_id) 做幂等，
     * 同一事件重复消费时影响 0 行。
     */
    int insertIgnore(OrderNotification notification);

    /** 按订单号统计通知条数（测试与排查用） */
    int countByOrderNo(@Param("orderNo") String orderNo);
}
