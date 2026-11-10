package com.example.springboot.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * MQ/事件消费幂等记录 Mapper。
 *
 * <p>消费端"至少一次"投递意味着同一事件可能来两次；用
 * {@code UNIQUE(consumer_name, event_id)} 做幂等：第二次插入影响 0 行，直接跳过。
 */
public interface MqConsumeLogMapper {

    @Insert("INSERT IGNORE INTO mq_consume_log (consumer_name, event_id) "
            + "VALUES (#{consumerName}, #{eventId})")
    int insertIgnore(@Param("consumerName") String consumerName,
                     @Param("eventId") String eventId);
}
