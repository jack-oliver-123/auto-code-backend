package com.jack.autocodebackend.controller;

import com.jack.autocodebackend.annotation.AuthCheck;
import com.jack.autocodebackend.config.OpenApiConfig;
import com.jack.autocodebackend.model.dto.AppChatRequestDTO;
import com.jack.autocodebackend.model.dto.AppPreviewRequestDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AppControllerOpenApiContractTest {

    @Test
    void everyProtectedApplicationOperationReferencesTheSessionCookieScheme() {
        assertThat(Stream.of(AppController.class, AppPreviewRedirectController.class)
                .flatMap(controller -> Arrays.stream(controller.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(AuthCheck.class)))
                .allSatisfy(method -> assertThat(method.getAnnotation(SecurityRequirement.class))
                        .as(method.getName())
                        .isNotNull()
                        .extracting(SecurityRequirement::name)
                        .isEqualTo(OpenApiConfig.SESSION_COOKIE_SCHEME));
    }

    @Test
    void generationContractDocumentsPostJsonAndSseCompletionSemantics() throws Exception {
        Method method = AppController.class.getDeclaredMethod(
                "chatToGenCode", AppChatRequestDTO.class, HttpServletRequest.class);

        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/chat/gen/code");
        assertThat(mapping.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        assertThat(mapping.produces()).containsExactly(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(method.getAnnotation(GetMapping.class)).isNull();

        Operation operation = method.getAnnotation(Operation.class);
        assertThat(operation.description())
                .contains("stored initPrompt", "require a non-blank message", "previewUrl",
                        "expiresAt", "bootstrap bearer", "immutable preview-snapshot",
                        "HttpOnly", "token-free preview-content", "same-site", "separate port",
                        "before AI generation",
                        "without a done event");

        io.swagger.v3.oas.annotations.parameters.RequestBody requestBody = method.getAnnotation(
                io.swagger.v3.oas.annotations.parameters.RequestBody.class);
        assertThat(requestBody.required()).isTrue();
        assertThat(requestBody.description()).contains("conditionally required");

        Map<String, ApiResponse> responses = Arrays.stream(
                        method.getAnnotation(ApiResponses.class).value())
                .collect(Collectors.toMap(ApiResponse::responseCode, response -> response));
        assertThat(responses).containsKeys("200", "400", "401", "403", "404", "500");
        assertThat(responses.get("200").content()).singleElement().satisfies(content -> {
            assertThat(content.mediaType()).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
            assertThat(content.schema().type()).isEqualTo("string");
            assertThat(content.schema().example().toString())
                    .contains("data:{\"d\":", "event:done", "previewUrl", "expiresAt",
                            "1753405723000");
        });
        assertThat(responses.values())
                .filteredOn(response -> !"SSE content events followed by one done event on success"
                        .equals(response.description()))
                .allSatisfy(response -> assertThat(response.content())
                        .singleElement()
                        .satisfies(content -> assertThat(content.mediaType())
                                .isEqualTo(MediaType.APPLICATION_JSON_VALUE)));
        assertThat(responses.get("500").description()).contains("without done");
    }

    @Test
    void previewSigningContractDocumentsPostJsonAndIsolation() throws Exception {
        Method method = AppController.class.getDeclaredMethod(
                "createAppPreview", AppPreviewRequestDTO.class, HttpServletRequest.class);

        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/preview");
        assertThat(mapping.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        assertThat(method.getParameters()[0].isAnnotationPresent(
                org.springframework.web.bind.annotation.RequestBody.class)).isTrue();

        Operation operation = method.getAnnotation(Operation.class);
        assertThat(operation.description())
                .contains("bootstrap bearer URL", "isolated preview origin", "HttpOnly",
                        "token-free URL", "immutable snapshot", "same-site", "separate",
                        "does not deploy");

        Map<String, ApiResponse> responses = Arrays.stream(
                        method.getAnnotation(ApiResponses.class).value())
                .collect(Collectors.toMap(ApiResponse::responseCode, response -> response));
        assertThat(responses).containsKeys("200", "400", "401", "403", "404", "500");
    }

    @Test
    void legacyPreviewContractDocumentsAuthenticatedTemporaryRedirect() throws Exception {
        Method method = AppPreviewRedirectController.class.getDeclaredMethod(
                "redirectToPreview", String.class, HttpServletRequest.class);

        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value())
                .containsExactly("/{codeOutputDirectory}", "/{codeOutputDirectory}/");

        Operation operation = method.getAnnotation(Operation.class);
        assertThat(operation.deprecated()).isTrue();
        assertThat(operation.description())
                .contains("never serves generated files", "fresh bootstrap bearer grant",
                        "307 redirect", "HttpOnly", "token-free snapshot URL", "same-site",
                        "distinct origin");

        Map<String, ApiResponse> responses = Arrays.stream(
                        method.getAnnotation(ApiResponses.class).value())
                .collect(Collectors.toMap(ApiResponse::responseCode, response -> response));
        assertThat(responses).containsKeys("307", "400", "401", "403", "404", "500");
    }

    @Test
    void messageFieldDocumentsItsConditionalRequirement() throws Exception {
        Field message = AppChatRequestDTO.class.getDeclaredField("message");

        Schema schema = message.getAnnotation(Schema.class);
        assertThat(schema.description())
                .contains("Ignored for the first generation", "required and non-blank");
    }
}
