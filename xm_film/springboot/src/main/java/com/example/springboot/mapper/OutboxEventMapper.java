package com.example.springboot.mapper;

import com.example.springboot.entity.OutboxEvent;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Outbox 事件表 Mapper。
 */
public interface OutboxEventMapper {

    int insert(OutboxEvent event);

    /** 取出待派发的事件（按创建时间升序，带重试时间窗判断） */
    List<OutboxEvent> selectPendingToDispatch(@Param("limit") int limit);

    /** 标记投递成功 */
    int markSent(@Param("id") Integer id);

    /** 标记投递失败并安排下次重试 */
    int markFailed(@Param("id") Integer id,
                   @Param("errorMsg") String errorMsg,
                   @Param("retryDelaySeconds") int retryDelaySeconds);

    /** 超过最大重试次数后终结 */
    int markDead(@Param("id") Integer id, @Param("errorMsg") String errorMsg);
}
