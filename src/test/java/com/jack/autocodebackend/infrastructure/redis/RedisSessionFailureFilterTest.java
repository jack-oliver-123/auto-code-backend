package com.jack.autocodebackend.infrastructure.redis;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RedisSessionFailureFilterTest {

    private RedisDependencyAvailability availability;

    private RedisSessionFailureFilter filter;

    @BeforeEach
    void setUp() {
        availability = new RedisDependencyAvailability(
                mock(ApplicationEventPublisher.class));
        availability.markAvailable();
        filter = new RedisSessionFailureFilter(
                new RedisConnectionFailureClassifier(),
                availability,
                JsonMapper.builder().build()
        );
    }

    @Test
    void isOrderedImmediatelyBeforeSpringSessionFilter() {
        Order order = RedisSessionFailureFilter.class.getAnnotation(Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        assertThat(RedisSessionFailureFilter.ORDER)
                .isLessThan(SessionRepositoryFilter.DEFAULT_ORDER);
    }

    @Test
    @SuppressWarnings("unchecked")
    void translatesSessionReadFailureToOneJson503() throws Exception {
        SessionRepository<MapSession> repository = mock(SessionRepository.class);
        given(repository.findById("session-id")).willThrow(
                new RedisConnectionFailureException("offline"));
        SessionRepositoryFilter<MapSession> sessionFilter =
                new SessionRepositoryFilter<>(repository);
        sessionFilter.setHttpSessionIdResolver(HeaderHttpSessionIdResolver.xAuthToken());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/protected");
        request.addHeader("X-Auth-Token", "session-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger controllerCalls = new AtomicInteger();

        filter.doFilter(request, response, (outerRequest, outerResponse) ->
                sessionFilter.doFilter(outerRequest, outerResponse,
                        (sessionRequest, sessionResponse) -> {
                            controllerCalls.incrementAndGet();
                            ((HttpServletRequest) sessionRequest).getSession(false);
                        }));

        assertDependencyUnavailable(response);
        assertThat(controllerCalls).hasValue(1);
        assertThat(availability.isAvailable()).isFalse();
        verify(repository).findById("session-id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginSaveFailureWritesNoCookieAndDoesNotRetrySessionSave() throws Exception {
        SessionRepository<MapSession> repository = mock(SessionRepository.class);
        MapSession newSession = new MapSession();
        given(repository.createSession()).willReturn(newSession);
        doThrow(new RedisConnectionFailureException("offline"))
                .when(repository).save(any(MapSession.class));
        SessionRepositoryFilter<MapSession> sessionFilter =
                new SessionRepositoryFilter<>(repository);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (outerRequest, outerResponse) ->
                sessionFilter.doFilter(outerRequest, outerResponse,
                        (sessionRequest, sessionResponse) ->
                                ((HttpServletRequest) sessionRequest)
                                        .getSession(true)
                                        .setAttribute("user_login", "authenticated")));

        assertDependencyUnavailable(response);
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        verify(repository, times(1)).save(any(MapSession.class));
    }

    @Test
    void preservesCorsHeadersAndClearsOnlyBufferedBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (chainRequest, chainResponse) -> {
            MockHttpServletResponse servletResponse =
                    (MockHttpServletResponse) chainResponse;
            servletResponse.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                    "http://localhost:5174");
            servletResponse.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            servletResponse.getOutputStream().write("old-body".getBytes(StandardCharsets.UTF_8));
            throw new RedisConnectionFailureException("offline");
        };

        filter.doFilter(request, response, chain);

        assertDependencyUnavailable(response);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://localhost:5174");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .isEqualTo("true");
        assertThat(response.getContentAsString()).doesNotContain("old-body");
    }

    @Test
    void replacesAnUncommittedWriterBodyWithJson503() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/writer");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (chainRequest, chainResponse) -> {
            chainResponse.getWriter().write("old-writer-body");
            throw new RedisConnectionFailureException("offline");
        });

        assertDependencyUnavailable(response);
        assertThat(response.getContentAsString()).doesNotContain("old-writer-body");
    }

    @Test
    void committedSseIsNotRewrittenAsJson() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (chainRequest, chainResponse) -> {
            MockHttpServletResponse servletResponse =
                    (MockHttpServletResponse) chainResponse;
            servletResponse.setStatus(200);
            servletResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            servletResponse.getOutputStream()
                    .write("data: partial\n\n".getBytes(StandardCharsets.UTF_8));
            servletResponse.flushBuffer();
            throw new RedisConnectionFailureException("offline");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(response.getContentAsString()).isEqualTo("data: partial\n\n");
        assertThat(availability.isAvailable()).isFalse();
    }

    @Test
    void doesNotTranslateMalformedSessionOrUnrelatedRuntimeFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/protected");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response,
                (chainRequest, chainResponse) -> {
                    throw new SerializationException("malformed");
                })).isInstanceOf(SerializationException.class);

        assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest("GET", "/protected"),
                new MockHttpServletResponse(),
                (chainRequest, chainResponse) -> {
                    throw new IllegalArgumentException("validation");
                })).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertDependencyUnavailable(MockHttpServletResponse response)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode json = JsonMapper.builder().build()
                .readTree(response.getContentAsString());
        assertThat(json.get("code").intValue()).isEqualTo(50300);
        assertThat(json.get("message").stringValue())
                .isEqualTo("依赖服务暂不可用");
    }
}
