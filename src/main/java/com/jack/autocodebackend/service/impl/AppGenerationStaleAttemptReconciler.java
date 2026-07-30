package com.jack.autocodebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jack.autocodebackend.config.AppGenerationProperties;
import com.jack.autocodebackend.core.lock.AppProcessingLeaseManager;
import com.jack.autocodebackend.mapper.AppMapper;
import com.jack.autocodebackend.model.domain.App;
import com.jack.autocodebackend.model.enums.AppGenerationStatusEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * Recovers bounded batches of abandoned latest attempts without racing a live lease owner.
 */
@Component
public class AppGenerationStaleAttemptReconciler {

    static final int BATCH_SIZE = 100;
    static final String FAILURE_CODE = "STALE_ATTEMPT";
    static final String FAILURE_MESSAGE = "生成任务异常中断，请重试";

    private static final Logger log =
            LoggerFactory.getLogger(AppGenerationStaleAttemptReconciler.class);

    private final AppMapper appMapper;
    private final AppProcessingLeaseManager leaseManager;
    private final AppGenerationProperties generationProperties;

    public AppGenerationStaleAttemptReconciler(
            AppMapper appMapper,
            AppProcessingLeaseManager leaseManager,
            AppGenerationProperties generationProperties
    ) {
        this.appMapper = appMapper;
        this.leaseManager = leaseManager;
        this.generationProperties = generationProperties;
    }

    @Scheduled(
            initialDelayString = "${app.generation.reconciliation-initial-delay-ms:60000}",
            fixedDelayString = "${app.generation.reconciliation-interval-ms:60000}"
    )
    public void reconcileScheduled() {
        try {
            reconcileOnce();
        } catch (RuntimeException exception) {
            log.warn("Stale generation attempt reconciliation failed");
        }
    }

    int reconcileOnce() {
        Date cutoff = new Date(System.currentTimeMillis()
                - generationProperties.getStaleAttemptAge().toMillis());
        QueryWrapper<App> query = new QueryWrapper<>();
        query.select("id", "userId", "generationAttemptId", "generationStartedTime")
                .eq("generationStatus", AppGenerationStatusEnum.GENERATING.getValue())
                .isNotNull("generationAttemptId")
                .isNotNull("generationStartedTime")
                .lt("generationStartedTime", cutoff)
                .orderByAsc("generationStartedTime", "id")
                .last("LIMIT " + BATCH_SIZE);
        List<App> candidates = appMapper.selectList(query);
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int reconciled = 0;
        for (App candidate : candidates) {
            AppProcessingLeaseManager.LeasePresence presence =
                    leaseManager.checkPresence(candidate.getId());
            if (presence != AppProcessingLeaseManager.LeasePresence.ABSENT) {
                continue;
            }
            reconciled += appMapper.failGenerationAttempt(
                    candidate.getId(),
                    candidate.getUserId(),
                    candidate.getGenerationAttemptId(),
                    FAILURE_CODE,
                    FAILURE_MESSAGE,
                    new Date()
            );
        }
        if (reconciled > 0) {
            log.info("Reconciled {} abandoned generation attempt(s)", reconciled);
        }
        return reconciled;
    }
}
