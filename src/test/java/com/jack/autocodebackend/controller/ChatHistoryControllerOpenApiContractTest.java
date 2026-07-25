package com.jack.autocodebackend.controller;

import com.jack.autocodebackend.annotation.AuthCheck;
import com.jack.autocodebackend.config.OpenApiConfig;
import com.jack.autocodebackend.model.dto.ChatHistoryAdminQueryDTO;
import com.jack.autocodebackend.model.dto.ChatHistoryCursorQueryDTO;
import com.jack.autocodebackend.model.vo.ChatHistoryCursorPageVO;
import com.jack.autocodebackend.model.vo.ChatHistoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ChatHistoryControllerOpenApiContractTest {

    @Test
    void everyHistoryOperationUsesPostJsonAndSessionCookieSecurity() {
        assertThat(Arrays.stream(ChatHistoryController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(AuthCheck.class)))
                .hasSize(2)
                .allSatisfy(method -> {
                    PostMapping mapping = method.getAnnotation(PostMapping.class);
                    assertThat(mapping).isNotNull();
                    assertThat(mapping.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
                    assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
                    assertThat(method.getAnnotation(SecurityRequirement.class))
                            .isNotNull()
                            .extracting(SecurityRequirement::name)
                            .isEqualTo(OpenApiConfig.SESSION_COOKIE_SCHEME);
                    assertThat(method.getAnnotation(
                            io.swagger.v3.oas.annotations.parameters.RequestBody.class))
                            .isNotNull()
                            .extracting(
                                    io.swagger.v3.oas.annotations.parameters.RequestBody::required)
                            .isEqualTo(true);
                });
    }

    @Test
    void cursorContractDocumentsStablePrependLoadingAndMajorResponses() throws Exception {
        Method method = ChatHistoryController.class.getDeclaredMethod(
                "listAppHistory", ChatHistoryCursorQueryDTO.class, HttpServletRequest.class);

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/list/page/vo");
        Operation operation = method.getAnnotation(Operation.class);
        assertThat(operation.description())
                .contains("owner", "administrator", "latest", "beforeId", "nextCursor",
                        "chronological");
        assertResponses(method, "200", "400", "401", "403", "404");

        Schema pageSize = ChatHistoryCursorQueryDTO.class.getDeclaredField("pageSize")
                .getAnnotation(Schema.class);
        assertThat(pageSize.defaultValue()).isEqualTo("10");
        assertThat(pageSize.minimum()).isEqualTo("1");
        assertThat(pageSize.maximum()).isEqualTo("20");
        assertThat(ChatHistoryCursorPageVO.class.getDeclaredField("nextCursor")
                .getAnnotation(Schema.class).description()).contains("hasMore", "null");
    }

    @Test
    void administratorContractDocumentsFiltersFixedOrderAndMajorResponses() throws Exception {
        Method method = ChatHistoryController.class.getDeclaredMethod(
                "listAllHistoryByAdmin", ChatHistoryAdminQueryDTO.class);

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/admin/list/page/vo");
        assertThat(method.getAnnotation(AuthCheck.class).mustRole()).isEqualTo("admin");
        assertThat(method.getAnnotation(Operation.class).description())
                .contains("appId", "userId", "messageType", "createTime", "id descending");
        assertResponses(method, "200", "400", "401", "403");

        Set<String> fields = Arrays.stream(ChatHistoryAdminQueryDTO.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrder(
                "pageNum", "pageSize", "appId", "userId", "messageType");
        assertThat(ChatHistoryAdminQueryDTO.class.getDeclaredField("messageType")
                .getAnnotation(Schema.class).allowableValues()).containsExactly("user", "ai");
    }

    @Test
    void historyViewContainsOnlyPublicContractFields() {
        Set<String> fields = Arrays.stream(ChatHistoryVO.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(fields).containsExactlyInAnyOrder(
                "id", "message", "messageType", "appId", "userId", "createTime");
    }

    private static void assertResponses(Method method, String... responseCodes) {
        Map<String, ApiResponse> responses = Arrays.stream(
                        method.getAnnotation(ApiResponses.class).value())
                .collect(Collectors.toMap(ApiResponse::responseCode, response -> response));
        assertThat(responses).containsKeys(responseCodes);
        assertThat(responses.values()).allSatisfy(response -> assertThat(response.content())
                .singleElement()
                .satisfies(content -> assertThat(content.mediaType())
                        .isEqualTo(MediaType.APPLICATION_JSON_VALUE)));
    }
}
