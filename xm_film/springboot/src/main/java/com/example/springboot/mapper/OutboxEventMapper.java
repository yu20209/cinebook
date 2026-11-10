package com.example.springboot.mapper;

import com.example.springboot.entity.OutboxEvent;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Outbox 事件表 Mapper。
 *
 * <p>派发采用"认领"模型：先原子地把一批事件标记为 PROCESSING 并写入实例标识与租约，
 * 再由认领者逐条投递。多实例同时运行时不会重复取到同一条事件；
 * 实例崩溃后租约到期，事件会被其它实例接走。
 */
public interface OutboxEventMapper {

    int insert(OutboxEvent event);

    /**
     * 认领一批待派发事件：PENDING 且到重试时间，或 PROCESSING 但租约已过期。
     * 使用 SKIP LOCKED 避免多个实例相互阻塞、重复认领。
     *
     * @return 实际认领的行数
     */
    int claimPending(@Param("owner") String owner,
                     @Param("leaseSeconds") int leaseSeconds,
                     @Param("limit") int limit);

    /** 取出本实例当前持有有效租约的事件 */
    List<OutboxEvent> selectClaimedBy(@Param("owner") String owner);

    /** 标记投递成功（仅限仍持有租约的认领者） */
    int markSent(@Param("id") Integer id, @Param("owner") String owner);

    /** 标记投递失败并安排下次重试（退回 PENDING，释放认领） */
    int markFailed(@Param("id") Integer id,
                   @Param("owner") String owner,
                   @Param("errorMsg") String errorMsg,
                   @Param("retryDelaySeconds") int retryDelaySeconds);

    /** 超过最大重试次数后终结 */
    int markDead(@Param("id") Integer id, @Param("owner") String owner, @Param("errorMsg") String errorMsg);
}
