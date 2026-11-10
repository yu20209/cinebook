package com.example.springboot.service;

import com.example.springboot.entity.Ordered;
import com.example.springboot.mapper.OrderedMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单幂等查询：用<b>独立事务</b>读已提交的订单。
 *
 * <p>为什么不能直接在 {@code OrderedService.createOrder} 的事务里查：
 * REPEATABLE-READ 下，本事务的快照可能早于并发对手的提交时刻。
 * 两个线程同时提交同一个 request_id 时，落败方在唯一键冲突后回头查，
 * 若用当前事务快照会查不到对手刚插入的行 —— 于是只能报"重复下单"，
 * 拿不到本应返回的原订单。
 *
 * <p>{@link Propagation#REQUIRES_NEW} 新开事务并读最新已提交数据，
 * 保证并发重复提交也能把原订单完整返回。
 */
@Service
public class OrderIdempotencyService {

    private final OrderedMapper orderedMapper;

    public OrderIdempotencyService(OrderedMapper orderedMapper) {
        this.orderedMapper = orderedMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Ordered findByUserAndRequestId(Integer userId, String requestId) {
        if (userId == null || requestId == null || requestId.isBlank()) {
            return null;
        }
        return orderedMapper.selectByUserAndRequestId(userId, requestId);
    }
}
