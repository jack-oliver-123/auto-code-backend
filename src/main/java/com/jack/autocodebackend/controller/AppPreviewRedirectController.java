package com.jack.autocodebackend.controller;

import com.jack.autocodebackend.annotation.AuthCheck;
import com.jack.autocodebackend.config.OpenApiConfig;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.exception.ThrowUtils;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.vo.AppPreviewVO;
import com.jack.autocodebackend.service.AppService;
import com.jack.autocodebackend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticated compatibility redirect for legacy generated-code preview URLs.
 */
@RestController
@RequestMapping("/static")
@Tag(name = "Application Preview", description = "Generated application preview compatibility")
public class AppPreviewRedirectController {

    private static final Pattern CODE_OUTPUT_DIRECTORY_PATTERN =
            Pattern.compile("^(?:html|multi_file)_([1-9]\\d*)$");

    private final AppService appService;

    private final UserService userService;

    public AppPreviewRedirectController(AppService appService, UserService userService) {
        this.appService = appService;
        this.userService = userService;
    }

    @GetMapping({"/{codeOutputDirectory}", "/{codeOutputDirectory}/"})
    @AuthCheck
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    @Operation(
            summary = "Redirect a legacy generated-code preview URL",
            description = "Owner-authenticated compatibility endpoint. It never serves generated files "
                    + "from the API origin; it creates a fresh bootstrap bearer grant and returns a "
                    + "307 redirect to the isolated preview origin, where the grant is exchanged for "
                    + "an HttpOnly, path-scoped cookie before loading a token-free snapshot URL. The preview "
                    + "origin must remain same-site with an iframe editor while still using a distinct origin.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "307", description = "Redirect to the isolated preview URL"),
            @ApiResponse(responseCode = "400", description = "Invalid generated-code directory name"),
            @ApiResponse(responseCode = "401", description = "Session cookie is missing or invalid"),
            @ApiResponse(responseCode = "403", description = "Caller is not the owner or must change password"),
            @ApiResponse(responseCode = "404", description = "Application does not exist"),
            @ApiResponse(responseCode = "500", description = "No complete generated preview is available")
    })
    public ResponseEntity<Void> redirectToPreview(
            @PathVariable String codeOutputDirectory,
            HttpServletRequest request
    ) {
        Long appId = parseAppId(codeOutputDirectory);
        User loginUser = userService.getLoginUser(request);
        AppPreviewVO preview = appService.createAppPreview(appId, loginUser);
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .location(URI.create(preview.getPreviewUrl()))
                .build();
    }

    private static Long parseAppId(String codeOutputDirectory) {
        Matcher matcher = CODE_OUTPUT_DIRECTORY_PATTERN.matcher(codeOutputDirectory);
        ThrowUtils.throwIf(!matcher.matches(), ErrorCode.PARAMS_ERROR,
                "生成代码目录名称不合法");
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "生成代码目录名称不合法"
            );
        }
    }
}
