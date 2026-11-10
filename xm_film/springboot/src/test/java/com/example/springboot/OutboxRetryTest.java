package com.example.springboot;

import com.example.springboot.entity.OutboxEvent;
import com.example.springboot.mapper.OutboxEventMapper;
import com.example.springboot.service.OrderEventPublisher;
import com.example.springboot.service.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 派发可靠性（任务 7）：成功标 SENT、失败按指数退避、超过上限转 FAILED 保留现场。
 */
@ExtendWith(MockitoExtension.class)
class OutboxRetryTest {

    @Mock
    OutboxEventMapper outboxEventMapper;

    @Mock
    OrderEventPublisher publisher;

    OutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxService(outboxEventMapper, publisher, new ObjectMapper());
    }

    @Test
    void successfulPublishMarksEventSent() {
        OutboxEvent event = event(1, 0);
        when(outboxEventMapper.selectPendingToDispatch(100)).thenReturn(List.of(event));

        int sent = outboxService.dispatchPending();

        assertThat(sent).isEqualTo(1);
        verify(outboxEventMapper).markSent(1);
        verify(outboxEventMapper, never()).markFailed(anyInt(), anyString(), anyInt());
    }

    @Test
    void failedPublishSchedulesFirstBackoffOfTenSeconds() {
        OutboxEvent event = event(2, 0);
        when(outboxEventMapper.selectPendingToDispatch(100)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(event);

        int sent = outboxService.dispatchPending();

        assertThat(sent).isZero();
        verify(outboxEventMapper).markFailed(eq(2), anyString(), eq(10));
        verify(outboxEventMapper, never()).markSent(anyInt());
    }

    @Test
    void failedPublishUsesExponentialBackoff() {
        OutboxEvent event = event(3, 2); // 10 * 3^2 = 90 秒
        when(outboxEventMapper.selectPendingToDispatch(100)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(event);

        outboxService.dispatchPending();

        verify(outboxEventMapper).markFailed(eq(3), anyString(), eq(90));
    }

    @Test
    void retryLimitTurnsEventFailedInsteadOfRetryingForever() {
        OutboxEvent event = event(4, 8);
        when(outboxEventMapper.selectPendingToDispatch(100)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(event);

        outboxService.dispatchPending();

        verify(outboxEventMapper).markDead(eq(4), anyString());
        verify(outboxEventMapper, never()).markFailed(anyInt(), anyString(), anyInt());
    }

    private static OutboxEvent event(int id, int retryCount) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setEventId("evt-" + id);
        event.setEventType(OutboxEvent.TYPE_ORDER_PAID);
        event.setAggregateId("ORDER-" + id);
        event.setPayload("{}");
        event.setRetryCount(retryCount);
        return event;
    }
}
