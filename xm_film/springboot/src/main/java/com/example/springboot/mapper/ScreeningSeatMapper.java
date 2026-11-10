package com.example.springboot.mapper;

import com.example.springboot.common.BaseMapper;
import com.example.springboot.entity.ScreeningSeat;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 场次座位库存 Mapper：座位级锁定/售出/释放。
 *
 * <p>所有条件更新都带 WHERE status = ? 守卫，只有状态符合预期时才允许变更 ——
 * 这是座位不会被重复售出的根本保证，不靠 JVM 锁也不靠串行化整场下单。
 */
public interface ScreeningSeatMapper extends BaseMapper<ScreeningSeat> {

    /**
     * 查询场次所有座位状态（用于选座图展示）
     */
    List<ScreeningSeat> selectByRecordId(@Param("recordId") Integer recordId);

    /**
     * 批量查询指定座位的当前状态（用于锁座前的预检查）
     */
    List<ScreeningSeat> selectByRecordIdAndSeatCodes(@Param("recordId") Integer recordId,
                                                     @Param("seatCodes") List<String> seatCodes);

    /**
     * 条件锁定座位：仅当 status = 0（可用）时才允许锁定，返回受影响行数
     * 多个座位逐个调用，全部返回 1 才算成功
     */
    int lockSeat(@Param("recordId") Integer recordId,
                 @Param("seatCode") String seatCode,
                 @Param("orderNo") String orderNo,
                 @Param("expireMinutes") int expireMinutes);

    /**
     * 批量锁定多个座位：同一事务内逐个条件更新
     * 若有任何一个座位已不可用，返回 0（调用方回滚）
     */
    int batchLockSeats(@Param("recordId") Integer recordId,
                       @Param("seatCodes") List<String> seatCodes,
                       @Param("orderNo") String orderNo,
                       @Param("expireMinutes") int expireMinutes);

    /**
     * 条件售出座位：仅当 lock_order_no = #{orderNo} 且 status = 1 时才允许转为已售出
     * 关键：防止订单 A 支付成功时，订单 B 已经锁定了同一座位（旧事务误更新新状态）
     */
    int markSold(@Param("recordId") Integer recordId,
                 @Param("seatCode") String seatCode,
                 @Param("orderNo") String orderNo);

    /**
     * 批量售出：支付成功后调用
     */
    int batchMarkSold(@Param("recordId") Integer recordId,
                      @Param("seatCodes") List<String> seatCodes,
                      @Param("orderNo") String orderNo);

    /**
     * 条件释放座位：取消/超时清理时调用
     * 必须带上 lock_order_no 条件，避免旧订单的延迟任务误释放后来用户已锁定的座位
     */
    int releaseSeat(@Param("recordId") Integer recordId,
                    @Param("seatCode") String seatCode,
                    @Param("orderNo") String orderNo);

    /**
     * 批量释放：订单取消/超时清理时调用
     */
    int batchReleaseSeats(@Param("recordId") Integer recordId,
                          @Param("seatCodes") List<String> seatCodes,
                          @Param("orderNo") String orderNo);

    /**
     * 根据订单号释放该订单锁定的所有座位（从订单 seat 字段解析座位号）
     * 仅释放 status=1（已锁定）的座位，供取消/超时清理使用
     */
    int releaseByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 根据订单号释放该订单已售出的座位（从订单 seat 字段解析座位号）
     * 仅释放 status=2（已售出）的座位，供退票使用
     */
    int releaseSoldByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 根据订单号将已锁定座位标记为已售出（支付成功后）
     */
    int markSoldByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 清理已过期但未被及时释放的锁定座位（补偿任务调用）
     */
    int cleanupExpiredLocks();
}
