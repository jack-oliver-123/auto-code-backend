package com.jack.autocodebackend.config;

import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MyBatisPlusConfigTest {

    @Test
    void paginationHasServerSideMaximum() {
        var interceptor = new MyBatisPlusConfig().mybatisPlusInterceptor();

        assertThat(interceptor.getInterceptors()).hasSize(1);
        PaginationInnerInterceptor pagination =
                (PaginationInnerInterceptor) interceptor.getInterceptors().getFirst();
        assertThat(pagination.getMaxLimit()).isEqualTo(100L);
    }
}
