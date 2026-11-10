package com.example.springboot;

import com.example.springboot.common.enums.ErrorCode;
import com.example.springboot.common.enums.RoleEnum;
import com.example.springboot.entity.Ordered;
import com.example.springboot.entity.Record;
import com.example.springboot.exception.CustomException;
import com.example.springboot.mapper.CinemaMapper;
import com.example.springboot.mapper.FilmMapper;
import com.example.springboot.mapper.OrderedMapper;
import com.example.springboot.mapper.RecordMapper;
import com.example.springboot.mapper.RoomMapper;
import com.example.springboot.mapper.ScreeningSeatMapper;
import com.example.springboot.service.OrderIdempotencyService;
import com.example.springboot.service.OrderedService;
import com.example.springboot.service.PayPasswordService;
import com.example.springboot.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下单幂等（任务 3）：
 * <ol>
 *   <li>同一 requestId 重试 → 直接返回原订单，不再锁座、不再插单；</li>
 *   <li>同一 requestId 却换了场次/座位 → 判定为请求内容冲突；</li>
 *   <li>并发重复提交（唯一键冲突） → 释放本次锁座，返回对手已提交的原订单而不是 409。</li>
 * </ol>
 */
@SpringBootTest(classes = OrderedService.class)
class OrderIdempotencyTest {

    @Autowired
    OrderedService orderedService;

    @MockBean
    OrderedMapper orderedMapper;

    @MockBean
    RecordMapper recordMapper;

    @MockBean
    FilmMapper filmMapper;

    @MockBean
    RoomMapper roomMapper;

    @MockBean
    CinemaMapper cinemaMapper;

    @MockBean
    ScreeningSeatMapper screeningSeatMapper;

    @MockBean
    WalletService walletService;

    @MockBean
    PayPasswordService payPasswordService;

    @MockBean
    OrderIdempotencyService idempotencyService;

    @BeforeEach
    void stub() {
        lenient().when(screeningSeatMapper.batchLockSeats(anyInt(), anyList(), anyString(), anyInt()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());
    }

    /** 重试命中已有订单：返回原订单，且没有任何锁座/落库动作 */
    @Test
    void retryWithSameRequestIdReturnsOriginalOrder() {
        when(idempotencyService.findByUserAndRequestId(8, "req-1")).thenReturn(existingOrder("1排1座"));

        Ordered ordered = new Ordered();
        ordered.setRecordId(1);
        ordered.setSeat("1排1座");
        ordered.setRequestId("req-1");

        orderedService.createOrder(ordered, RoleEnum.USER.name(), 8);

        assertThat(ordered.getId()).isEqualTo(77);
        assertThat(ordered.getOrders()).isEqualTo("ORDER-77");
        verifyNoInteractions(recordMapper);
        verify(screeningSeatMapper, never()).batchLockSeats(anyInt(), anyList(), anyString(), anyInt());
        verify(orderedMapper, never()).insert(any(Ordered.class));
    }

    /** 同一标识携带不同座位：不能把上次的订单当成这次的结果返回 */
    @Test
    void sameRequestIdWithDifferentSeatIsRejected() {
        when(idempotencyService.findByUserAndRequestId(8, "req-2")).thenReturn(existingOrder("1排2座"));

        Ordered ordered = new Ordered();
        ordered.setRecordId(1);
        ordered.setSeat("1排1座");
        ordered.setRequestId("req-2");

        assertThatThrownBy(() -> orderedService.createOrder(ordered, RoleEnum.USER.name(), 8))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("不同订单内容");
        verify(orderedMapper, never()).insert(any(Ordered.class));
    }

    /** 同一标识换了场次：同样属于内容冲突 */
    @Test
    void sameRequestIdWithDifferentRecordIsRejected() {
        Ordered existing = existingOrder("1排1座");
        existing.setRecordId(2);
        when(idempotencyService.findByUserAndRequestId(8, "req-3")).thenReturn(existing);

        Ordered ordered = new Ordered();
        ordered.setRecordId(1);
        ordered.setSeat("1排1座");
        ordered.setRequestId("req-3");

        assertThatThrownBy(() -> orderedService.createOrder(ordered, RoleEnum.USER.name(), 8))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("不同订单内容");
    }

    /**
     * 并发重复提交：预查时对手还没提交（返回 null），随后本事务插入撞上唯一键。
     * 期望：释放本次锁定的座位，再用独立事务读出原订单返回，调用方拿到的是同一笔订单。
     */
    @Test
    void concurrentDuplicateReleasesSeatsAndReturnsOriginalOrder() {
        when(recordMapper.selectById(1)).thenReturn(futureRecord());
        // 第一次（预查）没有，第二次（唯一键冲突后）能读到对手提交的订单
        when(idempotencyService.findByUserAndRequestId(8, "req-4"))
                .thenReturn(null, existingOrder("1排1座"));
        when(orderedMapper.insert(any(Ordered.class)))
                .thenThrow(new DuplicateKeyException("uk_ordered_user_request"));

        Ordered ordered = new Ordered();
        ordered.setRecordId(1);
        ordered.setSeat("1排1座");
        ordered.setRequestId("req-4");

        orderedService.createOrder(ordered, RoleEnum.USER.name(), 8);

        verify(screeningSeatMapper).releaseByOrderNo(anyString());
        assertThat(ordered.getId()).isEqualTo(77);
        assertThat(ordered.getOrders()).isEqualTo("ORDER-77");
    }

    /** 唯一键冲突但查不到原订单（例如并发删除）：仍按重复下单拒绝，不能静默成功 */
    @Test
    void duplicateWithoutVisibleOriginalOrderIsRejected() {
        when(recordMapper.selectById(1)).thenReturn(futureRecord());
        when(idempotencyService.findByUserAndRequestId(8, "req-5")).thenReturn(null);
        when(orderedMapper.insert(any(Ordered.class)))
                .thenThrow(new DuplicateKeyException("uk_ordered_user_request"));

        Ordered ordered = new Ordered();
        ordered.setRecordId(1);
        ordered.setSeat("1排1座");
        ordered.setRequestId("req-5");

        assertThatThrownBy(() -> orderedService.createOrder(ordered, RoleEnum.USER.name(), 8))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(ErrorCode.BUSINESS_CONFLICT.code());
        verify(screeningSeatMapper).releaseByOrderNo(anyString());
    }

    private static Ordered existingOrder(String seat) {
        Ordered existing = new Ordered();
        existing.setId(77);
        existing.setUserId(8);
        existing.setRecordId(1);
        existing.setSeat(seat);
        existing.setOrders("ORDER-77");
        existing.setStatus("待支付");
        return existing;
    }

    private static Record futureRecord() {
        Record recordRow = new Record();
        recordRow.setId(1);
        recordRow.setFilmId(24);
        recordRow.setCinemaId(10);
        recordRow.setRoomId(7);
        recordRow.setPrice("45.00");
        recordRow.setStart(LocalDateTime.now().plusDays(1).withNano(0)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return recordRow;
    }
}
