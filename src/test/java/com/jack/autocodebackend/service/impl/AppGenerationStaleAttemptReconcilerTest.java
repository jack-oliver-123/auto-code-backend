package com.jack.autocodebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jack.autocodebackend.config.AppGenerationProperties;
import com.jack.autocodebackend.core.lock.AppProcessingLeaseManager;
import com.jack.autocodebackend.mapper.AppMapper;
import com.jack.autocodebackend.model.domain.App;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SuppressWarnings({"rawtypes", "unchecked"})
class AppGenerationStaleAttemptReconcilerTest {

    private final AppMapper appMapper = mock(AppMapper.class);
    private final AppProcessingLeaseManager leaseManager =
            mock(AppProcessingLeaseManager.class);
    private final AppGenerationStaleAttemptReconciler reconciler =
            new AppGenerationStaleAttemptReconciler(
                    appMapper,
                    leaseManager,
                    AppGenerationProperties.defaults()
            );

    @Test
    void reconcilesOnlyExactAttemptWhoseLeaseIsConfirmedAbsent() {
        App absent = candidate(101L, 11L, "attempt-absent");
        App present = candidate(102L, 12L, "attempt-present");
        App uncertain = candidate(103L, 13L, "attempt-uncertain");
        given(appMapper.selectList(any(QueryWrapper.class)))
                .willReturn(List.of(absent, present, uncertain));
        given(leaseManager.checkPresence(101L))
                .willReturn(AppProcessingLeaseManager.LeasePresence.ABSENT);
        given(leaseManager.checkPresence(102L))
                .willReturn(AppProcessingLeaseManager.LeasePresence.PRESENT);
        given(leaseManager.checkPresence(103L))
                .willReturn(AppProcessingLeaseManager.LeasePresence.UNKNOWN);
        given(appMapper.failGenerationAttempt(
                eq(101L), eq(11L), eq("attempt-absent"),
                any(), any(), any(Date.class))).willReturn(1);

        assertThat(reconciler.reconcileOnce()).isEqualTo(1);

        verify(appMapper).failGenerationAttempt(
                eq(101L),
                eq(11L),
                eq("attempt-absent"),
                eq(AppGenerationStaleAttemptReconciler.FAILURE_CODE),
                eq(AppGenerationStaleAttemptReconciler.FAILURE_MESSAGE),
                any(Date.class)
        );
        verify(appMapper, never()).failGenerationAttempt(
                eq(102L), any(), any(), any(), any(), any());
        verify(appMapper, never()).failGenerationAttempt(
                eq(103L), any(), any(), any(), any(), any());

        ArgumentCaptor<QueryWrapper<App>> queryCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
        verify(appMapper).selectList(queryCaptor.capture());
        String sql = queryCaptor.getValue().getSqlSegment()
                .replaceAll("\\s+", "")
                .toLowerCase();
        assertThat(sql).contains(
                "generationstatus=",
                "generationstartedtime<",
                "orderbygenerationstartedtimeasc,idasc",
                "limit100");
    }

    @Test
    void emptyBatchDoesNotAskRedisOrMutateApplications() {
        given(appMapper.selectList(any(QueryWrapper.class))).willReturn(List.of());

        assertThat(reconciler.reconcileOnce()).isZero();

        verify(leaseManager, never()).checkPresence(any());
        verify(appMapper, never()).failGenerationAttempt(
                any(), any(), any(), any(), any(), any());
    }

    private static App candidate(long appId, long userId, String attemptId) {
        App app = new App();
        app.setId(appId);
        app.setUserId(userId);
        app.setGenerationAttemptId(attemptId);
        app.setGenerationStartedTime(new Date(1_000L));
        return app;
    }
}
