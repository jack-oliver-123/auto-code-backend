package com.jack.autocodebackend.model;

import com.jack.autocodebackend.model.domain.App;
import com.jack.autocodebackend.model.enums.AppGenerationStatusEnum;
import com.jack.autocodebackend.model.vo.AppDetailVO;
import com.jack.autocodebackend.model.vo.AppVO;
import com.jack.autocodebackend.model.vo.PublicAppDetailVO;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class AppGenerationLifecycleContractTest {

    @Test
    void persistedEnumValuesAreStableAndRejectUnknownInput() {
        assertThat(Arrays.stream(AppGenerationStatusEnum.values())
                .map(AppGenerationStatusEnum::getValue))
                .containsExactly("PENDING", "GENERATING", "SUCCEEDED", "FAILED");
        assertThat(AppGenerationStatusEnum.getEnumByValue("SUCCEEDED"))
                .isEqualTo(AppGenerationStatusEnum.SUCCEEDED);
        assertThat(AppGenerationStatusEnum.getEnumByValue("succeeded")).isNull();
        assertThat(AppGenerationStatusEnum.getEnumByValue(" ")).isNull();
    }

    @Test
    void domainAndViewsKeepAttemptIdentityPrivate() throws Exception {
        assertThat(App.class.getDeclaredField("generationStatus")).isNotNull();
        assertThat(App.class.getDeclaredField("generationAttemptId")).isNotNull();
        assertThat(App.class.getDeclaredField("generationFailureCode")).isNotNull();
        assertThat(App.class.getDeclaredField("generationFailureMessage")).isNotNull();
        assertThat(App.class.getDeclaredField("generationStartedTime")).isNotNull();
        assertThat(App.class.getDeclaredField("generationFinishedTime")).isNotNull();

        assertThat(AppVO.class.getDeclaredField("generationStatus")).isNotNull();
        assertThat(AppDetailVO.class.getDeclaredField("generationFailureCode")).isNotNull();
        assertThat(AppDetailVO.class.getDeclaredField("generationFailureMessage")).isNotNull();
        assertThat(PublicAppDetailVO.class.getDeclaredField("generationStatus")).isNotNull();
        assertThat(findField(AppVO.class, "generationAttemptId")).isFalse();
        assertThat(findField(AppVO.class, "generationFailureMessage")).isFalse();
        assertThat(findField(AppDetailVO.class, "generationAttemptId")).isFalse();
        assertThat(findField(PublicAppDetailVO.class, "generationAttemptId")).isFalse();
        assertThat(findField(PublicAppDetailVO.class, "generationFailureCode")).isFalse();
        assertThat(findField(PublicAppDetailVO.class, "generationFailureMessage")).isFalse();
    }

    @Test
    void mapperAndCanonicalMigrationContainEveryLifecycleColumnAndExactCas() throws Exception {
        String mapper = Files.readString(
                Path.of("src", "main", "resources", "mapper", "AppMapper.xml"),
                StandardCharsets.UTF_8);
        assertThat(mapper).contains(
                "property=\"generationStatus\" column=\"generationStatus\"",
                "property=\"generationAttemptId\" column=\"generationAttemptId\"",
                "id=\"startGenerationAttempt\"",
                "id=\"completeGenerationAttempt\"",
                "id=\"failGenerationAttempt\"",
                "generationAttemptId = #{attemptId}",
                "generationStatus = 'GENERATING'");

        String migration = Files.readString(
                Path.of("sql", "init.sql"), StandardCharsets.UTF_8);
        assertThat(migration).contains(
                "generationStatus         varchar(32)  default 'PENDING' not null",
                "idx_generationStatus_startedTime_id",
                "WHEN codeGenType IS NOT NULL THEN 'SUCCEEDED'",
                "ELSE 'PENDING'",
                "MODIFY COLUMN generationStatus varchar(32) DEFAULT 'PENDING' NOT NULL");
    }

    private static boolean findField(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredFields())
                .anyMatch(field -> field.getName().equals(name));
    }
}
