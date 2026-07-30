package com.jack.autocodebackend.infrastructure.redis;

import com.jack.autocodebackend.common.BaseResponse;
import com.jack.autocodebackend.common.ResultUtils;
import com.jack.autocodebackend.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Order(RedisSessionFailureFilter.ORDER)
@Slf4j
public class RedisSessionFailureFilter extends OncePerRequestFilter {

    public static final int ORDER = SessionRepositoryFilter.DEFAULT_ORDER - 1;

    private final RedisConnectionFailureClassifier failureClassifier;

    private final RedisDependencyAvailability availability;

    private final ObjectMapper objectMapper;

    public RedisSessionFailureFilter(
            RedisConnectionFailureClassifier failureClassifier,
            RedisDependencyAvailability availability,
            ObjectMapper objectMapper
    ) {
        this.failureClassifier = failureClassifier;
        this.availability = availability;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            if (!failureClassifier.isConnectionFailure(exception)) {
                throw exception;
            }
            availability.markUnavailable();
            if (response.isCommitted()) {
                log.warn("Redis dependency unavailable after HTTP response commit: "
                        + "operation=http-session");
                return;
            }
            writeDependencyUnavailable(response);
            log.warn("Redis dependency unavailable: operation=http-session");
        }
    }

    private void writeDependencyUnavailable(HttpServletResponse response) throws IOException {
        BaseResponse<?> body = ResultUtils.error(ErrorCode.DEPENDENCY_UNAVAILABLE);
        byte[] json = objectMapper.writeValueAsBytes(body);
        response.resetBuffer();
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(json.length);
        try {
            response.getOutputStream().write(json);
        } catch (IllegalStateException writerAlreadySelected) {
            String asciiJson = objectMapper.writer()
                    .with(JsonWriteFeature.ESCAPE_NON_ASCII)
                    .writeValueAsString(body);
            Charset responseCharset = Charset.forName(response.getCharacterEncoding());
            response.setContentLength(asciiJson.getBytes(responseCharset).length);
            response.getWriter().write(asciiJson);
        }
    }
}
