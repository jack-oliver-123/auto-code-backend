package com.jack.autocodebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jack.autocodebackend.model.domain.App;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * 针对表【app（应用）】的数据库操作 Mapper
 */
public interface AppMapper extends BaseMapper<App> {

    int startGenerationAttempt(
            @Param("appId") Long appId,
            @Param("userId") Long userId,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedAttemptId") String expectedAttemptId,
            @Param("attemptId") String attemptId,
            @Param("startedTime") Date startedTime
    );

    int completeGenerationAttempt(
            @Param("appId") Long appId,
            @Param("userId") Long userId,
            @Param("attemptId") String attemptId,
            @Param("finishedTime") Date finishedTime
    );

    int failGenerationAttempt(
            @Param("appId") Long appId,
            @Param("userId") Long userId,
            @Param("attemptId") String attemptId,
            @Param("failureCode") String failureCode,
            @Param("failureMessage") String failureMessage,
            @Param("finishedTime") Date finishedTime
    );

}
