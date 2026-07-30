package com.jack.autocodebackend.controller;

import com.jack.autocodebackend.common.BaseResponse;
import com.jack.autocodebackend.common.ResultUtils;
import com.jack.autocodebackend.core.vue.VueBuilderDependencyProbe;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.infrastructure.redis.RedisDependencyProbe;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthController {

    private static final String REDIS_DEPENDENCY = "redis";
    private static final String VUE_BUILDER_DEPENDENCY = "vue-builder";

    private final RedisDependencyProbe redisDependencyProbe;
    private final VueBuilderDependencyProbe vueBuilderDependencyProbe;

    public HealthController(
            RedisDependencyProbe redisDependencyProbe,
            VueBuilderDependencyProbe vueBuilderDependencyProbe
    ) {
        this.redisDependencyProbe = redisDependencyProbe;
        this.vueBuilderDependencyProbe = vueBuilderDependencyProbe;
    }

    @GetMapping({"/check", "/live"})
    public BaseResponse<String> liveness() {
        return ResultUtils.success("ok");
    }

    @GetMapping("/ready")
    public ResponseEntity<BaseResponse<?>> readiness() {
        if (!redisDependencyProbe.checkReadiness()) {
            return unavailable(REDIS_DEPENDENCY);
        }
        if (!vueBuilderDependencyProbe.checkReadiness()) {
            return unavailable(VUE_BUILDER_DEPENDENCY);
        }
        return ResponseEntity.ok(ResultUtils.success("ready"));
    }

    private ResponseEntity<BaseResponse<?>> unavailable(String dependency) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new BaseResponse<>(
                        ErrorCode.DEPENDENCY_UNAVAILABLE.getCode(),
                        dependency,
                        ErrorCode.DEPENDENCY_UNAVAILABLE.getMessage()
                ));
    }
}
