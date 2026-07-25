package com.jack.autocodebackend.core.deploy;

import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.config.AppDeploymentLocalServerProperties;
import com.jack.autocodebackend.config.AppDeploymentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppDeploymentLocalServerTest {

    private static final String DEPLOY_KEY = "Ab3xY9";

    private static final long PREVIEW_APP_ID = 41L;

    private static final Pattern PREVIEW_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final String INDEX = "<!doctype html><link rel=\"stylesheet\" href=\"style.css\">";

    private static final String STYLE = "body { color: #123456; }";

    private static final String SCRIPT = "console.log('preview');";

    @TempDir
    Path temporaryRoot;

    private Path deploymentRoot;

    private Path previewOutputRoot;

    private Path previewSnapshotRoot;

    private MutableClock clock;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private AppDeploymentLocalServer localServer;

    private URI serverOrigin;

    @BeforeEach
    void createRoots() throws IOException {
        deploymentRoot = Files.createDirectory(temporaryRoot.resolve("deploy"));
        previewOutputRoot = Files.createDirectory(temporaryRoot.resolve("output"));
        previewSnapshotRoot = temporaryRoot.resolve("preview");
        clock = new MutableClock(
                Instant.parse("2026-07-25T08:00:00Z"),
                ZoneOffset.UTC
        );
    }

    @AfterEach
    void stopServer() {
        if (localServer != null) {
            localServer.destroy();
        }
    }

    @Test
    void servesEntryPointRedirectAndHeadOverRealHttp() throws Exception {
        createDeploymentFixture();
        startServer();

        HttpResponse<byte[]> redirect = request("GET", "/" + DEPLOY_KEY);
        assertEquals(308, redirect.statusCode());
        assertEquals("/" + DEPLOY_KEY + "/", redirect.headers().firstValue("Location").orElseThrow());
        assertSecurityHeaders(redirect);

        HttpResponse<byte[]> index = request("GET", "/" + DEPLOY_KEY + "/");
        assertEquals(200, index.statusCode());
        assertEquals(INDEX, new String(index.body(), StandardCharsets.UTF_8));
        assertEquals(
                "text/html; charset=UTF-8",
                index.headers().firstValue("Content-Type").orElseThrow()
        );
        assertSecurityHeaders(index);

        HttpResponse<byte[]> head = request("HEAD", "/" + DEPLOY_KEY + "/");
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        assertEquals(
                Integer.toString(INDEX.getBytes(StandardCharsets.UTF_8).length),
                head.headers().firstValue("Content-Length").orElseThrow()
        );
        assertSecurityHeaders(head);
    }

    @Test
    void servesAssetsWithDeterministicMimeTypesAndNeverListsDirectories() throws Exception {
        Path keyDirectory = createDeploymentFixture();
        Path assets = Files.createDirectory(keyDirectory.resolve("assets"));
        Files.write(assets.resolve("logo.bin"), new byte[]{0, 1, 2, 3});
        startServer();

        HttpResponse<byte[]> css = request("GET", "/" + DEPLOY_KEY + "/style.css");
        assertEquals(200, css.statusCode());
        assertEquals(STYLE, new String(css.body(), StandardCharsets.UTF_8));
        assertEquals("text/css; charset=UTF-8", css.headers().firstValue("Content-Type").orElseThrow());

        HttpResponse<byte[]> javascript = request("GET", "/" + DEPLOY_KEY + "/script.js");
        assertEquals(200, javascript.statusCode());
        assertEquals(SCRIPT, new String(javascript.body(), StandardCharsets.UTF_8));
        assertEquals(
                "text/javascript; charset=UTF-8",
                javascript.headers().firstValue("Content-Type").orElseThrow()
        );

        HttpResponse<byte[]> binary = request("GET", "/" + DEPLOY_KEY + "/assets/logo.bin");
        assertEquals(200, binary.statusCode());
        assertEquals("application/octet-stream", binary.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(4, binary.body().length);

        assertEquals(404, request("GET", "/" + DEPLOY_KEY + "/assets").statusCode());
        assertEquals(404, request("GET", "/" + DEPLOY_KEY + "/assets/").statusCode());
    }

    @Test
    void rejectsUnsupportedMethodsWithoutCredentialedCors() throws Exception {
        createDeploymentFixture();
        startServer();

        HttpResponse<byte[]> post = request("POST", "/" + DEPLOY_KEY + "/");
        assertEquals(405, post.statusCode());
        assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElseThrow());
        assertSecurityHeaders(post);
        assertTrue(post.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertTrue(post.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());

        HttpResponse<byte[]> options = request("OPTIONS", "/" + DEPLOY_KEY + "/");
        assertEquals(405, options.statusCode());
        assertTrue(options.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertTrue(options.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
    }

    @Test
    void rejectsTraversalHiddenTemporaryAndMissingPaths() throws Exception {
        Path keyDirectory = createDeploymentFixture();
        Files.writeString(keyDirectory.resolve(".env"), "SECRET=value", StandardCharsets.UTF_8);
        Path temporaryDirectory = Files.createDirectory(deploymentRoot.resolve(".staging-Ab3xY9"));
        Files.writeString(temporaryDirectory.resolve("index.html"), "temporary", StandardCharsets.UTF_8);
        Files.writeString(deploymentRoot.resolveSibling("outside.txt"), "outside", StandardCharsets.UTF_8);
        startServer();

        String[] rejectedPaths = {
                "/",
                "/not-a-key/",
                "/.staging-Ab3xY9/index.html",
                "/" + DEPLOY_KEY + "/.env",
                "/" + DEPLOY_KEY + "/missing.txt",
                "/" + DEPLOY_KEY + "/%2e%2e/outside.txt",
                "/" + DEPLOY_KEY + "/%2E%2E%2Foutside.txt",
                "/" + DEPLOY_KEY + "/%2e%2e%5Coutside.txt",
                "/" + DEPLOY_KEY + "//index.html"
        };
        for (String path : rejectedPaths) {
            HttpResponse<byte[]> response = request("GET", path);
            assertEquals(404, response.statusCode(), path);
            assertSecurityHeaders(response);
        }
    }

    @Test
    void rejectsSymlinkDeployKeyDirectoryAndNestedFile() throws Exception {
        Path keyDirectory = createDeploymentFixture();
        Path outsideDirectory = Files.createDirectory(deploymentRoot.resolve("outside"));
        Files.writeString(outsideDirectory.resolve("index.html"), "outside index", StandardCharsets.UTF_8);
        Path alternateKeyLink = deploymentRoot.resolve("Zy9K2m");
        createSymbolicLinkOrSkip(alternateKeyLink, outsideDirectory);

        Path outsideFile = deploymentRoot.resolveSibling("outside-secret.txt");
        Files.writeString(outsideFile, "secret", StandardCharsets.UTF_8);
        createSymbolicLinkOrSkip(keyDirectory.resolve("leak.txt"), outsideFile);
        startServer();

        assertEquals(404, request("GET", "/Zy9K2m/").statusCode());
        assertEquals(404, request("GET", "/" + DEPLOY_KEY + "/leak.txt").statusCode());
    }

    @Test
    void issuesPreviewAndServesItsEntryPointAndAssetsOverRealHttp() throws Exception {
        createPreviewFixture(CodeGenTypeEnum.MULTI_FILE, PREVIEW_APP_ID);
        startServer();

        AppDeploymentLocalServer.PreviewAccess access = localServer.issuePreview(
                PREVIEW_APP_ID,
                CodeGenTypeEnum.MULTI_FILE
        );
        URI previewUri = URI.create(access.url());
        String token = previewToken(access);
        assertTrue(PREVIEW_TOKEN_PATTERN.matcher(token).matches());
        assertEquals(clock.instant().plus(Duration.ofMinutes(15)), access.expiresAt());
        assertEquals(serverOrigin.getPort(), previewUri.getPort());

        String bootstrapRoot = previewUri.getRawPath();
        HttpResponse<byte[]> redirect = request(
                "GET",
                bootstrapRoot.substring(0, bootstrapRoot.length() - 1)
        );
        assertEquals(308, redirect.statusCode());
        assertEquals(bootstrapRoot, redirect.headers().firstValue("Location").orElseThrow());
        assertPreviewSecurityHeaders(redirect);

        PreviewSession session = bootstrap(access);
        assertFalse(session.contentRoot().contains(token));
        assertTrue(PREVIEW_TOKEN_PATTERN.matcher(session.publicId()).matches());
        assertTrue(session.setCookie().contains("Path=" + session.contentRoot()));
        assertTrue(session.setCookie().contains("Max-Age=900"));
        assertTrue(session.setCookie().contains("HttpOnly"));
        assertTrue(session.setCookie().contains("SameSite=Strict"));

        HttpResponse<byte[]> missingCookie = request("GET", session.contentRoot());
        assertEquals(404, missingCookie.statusCode());
        assertPreviewSecurityHeaders(missingCookie);

        HttpResponse<byte[]> index = requestWithCookie(
                "GET",
                session.contentRoot(),
                session.cookiePair()
        );
        assertEquals(200, index.statusCode());
        assertEquals(INDEX, new String(index.body(), StandardCharsets.UTF_8));
        assertEquals(
                "text/html; charset=UTF-8",
                index.headers().firstValue("Content-Type").orElseThrow()
        );
        assertPreviewSecurityHeaders(index);

        HttpResponse<byte[]> css = requestWithCookie(
                "GET",
                session.contentRoot() + "style.css",
                session.cookiePair()
        );
        assertEquals(200, css.statusCode());
        assertEquals(STYLE, new String(css.body(), StandardCharsets.UTF_8));
        assertEquals(
                "text/css; charset=UTF-8",
                css.headers().firstValue("Content-Type").orElseThrow()
        );
        assertPreviewSecurityHeaders(css);

        HttpResponse<byte[]> head = requestWithCookie(
                "HEAD",
                session.contentRoot(),
                session.cookiePair()
        );
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        assertEquals(
                Integer.toString(INDEX.getBytes(StandardCharsets.UTF_8).length),
                head.headers().firstValue("Content-Length").orElseThrow()
        );
    }

    @Test
    void failedRefreshKeepsV1SnapshotAndSuccessfulRefreshRotatesToV2() throws Exception {
        Path source = createPreviewFixture(CodeGenTypeEnum.MULTI_FILE, PREVIEW_APP_ID);
        startServer();

        AppDeploymentLocalServer.PreviewAccess first = localServer.issuePreview(
                PREVIEW_APP_ID,
                CodeGenTypeEnum.MULTI_FILE
        );
        PreviewSession firstSession = bootstrap(first);
        Path firstSnapshot = previewSnapshotRoot.resolve(firstSession.publicId());
        assertTrue(Files.isDirectory(firstSnapshot));
        assertEquals(
                INDEX,
                new String(requestWithCookie(
                        "GET",
                        firstSession.contentRoot(),
                        firstSession.cookiePair()
                ).body(), StandardCharsets.UTF_8)
        );

        String indexV2 = "<!doctype html><h1>version two</h1>";
        Files.writeString(source.resolve("index.html"), indexV2, StandardCharsets.UTF_8);
        Files.delete(source.resolve("script.js"));
        assertThrows(
                IllegalStateException.class,
                () -> localServer.issuePreview(PREVIEW_APP_ID, CodeGenTypeEnum.MULTI_FILE)
        );
        assertTrue(Files.isDirectory(firstSnapshot));
        assertEquals(
                INDEX,
                new String(requestWithCookie(
                        "GET",
                        firstSession.contentRoot(),
                        firstSession.cookiePair()
                ).body(), StandardCharsets.UTF_8)
        );

        Files.writeString(source.resolve("script.js"), SCRIPT, StandardCharsets.UTF_8);
        AppDeploymentLocalServer.PreviewAccess second = localServer.issuePreview(
                PREVIEW_APP_ID,
                CodeGenTypeEnum.MULTI_FILE
        );
        PreviewSession secondSession = bootstrap(second);

        assertNotEquals(first.url(), second.url());
        assertEquals(404, request("GET", URI.create(first.url()).getRawPath()).statusCode());
        assertEquals(
                404,
                requestWithCookie(
                        "GET",
                        firstSession.contentRoot(),
                        firstSession.cookiePair()
                ).statusCode()
        );
        assertFalse(Files.exists(firstSnapshot));
        assertEquals(
                indexV2,
                new String(requestWithCookie(
                        "GET",
                        secondSession.contentRoot(),
                        secondSession.cookiePair()
                ).body(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void expiresAndRevokesPreviewAccessAndDeletesSnapshots() throws Exception {
        createPreviewFixture(CodeGenTypeEnum.HTML, PREVIEW_APP_ID);
        startServer();
        AppDeploymentLocalServer.PreviewAccess access = localServer.issuePreview(
                PREVIEW_APP_ID,
                CodeGenTypeEnum.HTML
        );
        PreviewSession session = bootstrap(access);
        Path snapshot = previewSnapshotRoot.resolve(session.publicId());
        assertTrue(Files.isDirectory(snapshot));

        clock.advance(Duration.ofMinutes(15).minusMillis(1));
        assertEquals(
                200,
                requestWithCookie("GET", session.contentRoot(), session.cookiePair()).statusCode()
        );

        clock.advance(Duration.ofMillis(1));
        assertEquals(404, request("GET", URI.create(access.url()).getRawPath()).statusCode());
        assertFalse(Files.exists(snapshot));

        AppDeploymentLocalServer.PreviewAccess revocable = localServer.issuePreview(
                PREVIEW_APP_ID,
                CodeGenTypeEnum.HTML
        );
        PreviewSession revocableSession = bootstrap(revocable);
        Path revocableSnapshot = previewSnapshotRoot.resolve(revocableSession.publicId());
        assertTrue(Files.isDirectory(revocableSnapshot));

        localServer.revokePreview(PREVIEW_APP_ID);
        assertEquals(
                404,
                requestWithCookie(
                        "GET",
                        revocableSession.contentRoot(),
                        revocableSession.cookiePair()
                ).statusCode()
        );
        assertFalse(Files.exists(revocableSnapshot));
    }

    @Test
    void issuesPreviewOnlyWhileRunningForACompleteSafeSource() throws Exception {
        createPreviewFixture(CodeGenTypeEnum.MULTI_FILE, PREVIEW_APP_ID);
        localServer = newLocalServer(
                "http://127.0.0.1:0",
                new AppDeploymentLocalServerProperties(true, "127.0.0.1", 0)
        );

        assertThrows(
                IllegalStateException.class,
                () -> localServer.issuePreview(PREVIEW_APP_ID, CodeGenTypeEnum.MULTI_FILE)
        );
        assertThrows(IllegalStateException.class, localServer::requirePreviewAvailable);

        localServer.start();
        serverOrigin = URI.create("http://127.0.0.1:" + localServer.getAddress().getPort());
        localServer.requirePreviewAvailable();
        assertThrows(
                IllegalStateException.class,
                () -> localServer.issuePreview(PREVIEW_APP_ID + 1, CodeGenTypeEnum.MULTI_FILE)
        );

        long incompleteAppId = PREVIEW_APP_ID + 2;
        Path incomplete = Files.createDirectory(
                previewOutputRoot.resolve("multi_file_" + incompleteAppId)
        );
        Files.writeString(incomplete.resolve("index.html"), INDEX, StandardCharsets.UTF_8);
        assertThrows(
                IllegalStateException.class,
                () -> localServer.issuePreview(incompleteAppId, CodeGenTypeEnum.MULTI_FILE)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> localServer.issuePreview(0, CodeGenTypeEnum.HTML)
        );
        assertThrows(
                NullPointerException.class,
                () -> localServer.issuePreview(PREVIEW_APP_ID, null)
        );
        assertThrows(IllegalArgumentException.class, () -> localServer.revokePreview(0));
    }

    @Test
    void rejectsPreviewSourceContainingASymbolicLink() throws Exception {
        Path previewDirectory = createPreviewFixture(
                CodeGenTypeEnum.MULTI_FILE,
                PREVIEW_APP_ID
        );
        Path outside = temporaryRoot.resolve("outside-preview.txt");
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        createSymbolicLinkOrSkip(previewDirectory.resolve("leak.txt"), outside);
        startServer();

        assertThrows(
                IllegalStateException.class,
                () -> localServer.issuePreview(PREVIEW_APP_ID, CodeGenTypeEnum.MULTI_FILE)
        );
    }

    @Test
    void keepsDeploymentRoutesAndRejectsPreviewBypassPaths() throws Exception {
        createDeploymentFixture();
        Path previewDirectory = createPreviewFixture(
                CodeGenTypeEnum.MULTI_FILE,
                PREVIEW_APP_ID
        );
        startServer();
        AppDeploymentLocalServer.PreviewAccess access = localServer.issuePreview(
                PREVIEW_APP_ID,
                CodeGenTypeEnum.MULTI_FILE
        );
        PreviewSession session = bootstrap(access);
        String previewRoot = session.contentRoot();

        Files.writeString(previewDirectory.resolve(".env"), "SECRET=value", StandardCharsets.UTF_8);
        Path assets = Files.createDirectory(previewDirectory.resolve("assets"));
        Files.write(assets.resolve("logo.bin"), new byte[]{0, 1, 2});

        assertEquals(200, request("GET", "/" + DEPLOY_KEY + "/").statusCode());
        assertEquals(
                404,
                request("GET", "/multi_file_" + PREVIEW_APP_ID + "/").statusCode()
        );
        assertEquals(
                404,
                request("GET", "/preview/" + "A".repeat(43) + "/").statusCode()
        );

        String[] rejectedPaths = {
                previewRoot + ".env",
                previewRoot + "assets/",
                previewRoot + "%2e%2e/outside.txt",
                previewRoot + "%2E%2E%2Foutside.txt",
                previewRoot + "%2e%2e%5Coutside.txt",
                previewRoot + "/index.html"
        };
        for (String path : rejectedPaths) {
            HttpResponse<byte[]> response = requestWithCookie(
                    "GET",
                    path,
                    session.cookiePair()
            );
            assertEquals(404, response.statusCode(), path);
            assertPreviewSecurityHeaders(response);
        }

        HttpResponse<byte[]> post = requestWithCookie(
                "POST",
                previewRoot,
                session.cookiePair()
        );
        assertEquals(405, post.statusCode());
        assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElseThrow());
        assertPreviewSecurityHeaders(post);
    }

    @Test
    void startsOnlyForTheMainWebServerEvent() throws Exception {
        createDeploymentFixture();
        localServer = newLocalServer(
                "http://127.0.0.1:0",
                new AppDeploymentLocalServerProperties(true, "127.0.0.1", 0)
        );
        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        WebServerApplicationContext context = mock(WebServerApplicationContext.class);
        when(event.getApplicationContext()).thenReturn(context);
        when(context.getServerNamespace()).thenReturn("management");

        localServer.onWebServerInitialized(event);
        assertFalse(localServer.isRunning());

        when(context.getServerNamespace()).thenReturn(null);
        localServer.onWebServerInitialized(event);
        assertTrue(localServer.isRunning());
    }

    @Test
    void startCleansOrphansAndDestroyCleansOwnedSnapshots() throws Exception {
        Files.createDirectories(previewSnapshotRoot);
        Files.createDirectory(previewSnapshotRoot.resolve("preview-staging-orphan"));
        Files.createDirectory(previewSnapshotRoot.resolve("A".repeat(43)));
        createPreviewFixture(CodeGenTypeEnum.HTML, PREVIEW_APP_ID);

        startServer();
        assertEquals(0, countPreviewRootEntries());

        AppDeploymentLocalServer.PreviewAccess access = localServer.issuePreview(
                PREVIEW_APP_ID,
                CodeGenTypeEnum.HTML
        );
        PreviewSession session = bootstrap(access);
        assertTrue(Files.isDirectory(previewSnapshotRoot.resolve(session.publicId())));

        localServer.destroy();
        assertEquals(0, countPreviewRootEntries());
    }

    @Test
    void bindFailureAndDisabledDestroyDoNotCleanAnotherInstancesSnapshots() throws Exception {
        createPreviewFixture(CodeGenTypeEnum.HTML, PREVIEW_APP_ID);
        startServer();
        PreviewSession session = bootstrap(localServer.issuePreview(
                PREVIEW_APP_ID,
                CodeGenTypeEnum.HTML
        ));
        Path activeSnapshot = previewSnapshotRoot.resolve(session.publicId());
        assertTrue(Files.isDirectory(activeSnapshot));

        int occupiedPort = localServer.getAddress().getPort();
        AppDeploymentLocalServer competingServer = new AppDeploymentLocalServer(
                new AppDeploymentProperties(
                        deploymentRoot,
                        "http://127.0.0.1:" + occupiedPort
                ),
                new AppDeploymentLocalServerProperties(
                        true,
                        "127.0.0.1",
                        occupiedPort
                ),
                previewOutputRoot,
                previewSnapshotRoot,
                clock,
                new SecureRandom(),
                new NioFileTreeOperations()
        );
        assertThrows(IllegalStateException.class, competingServer::start);
        competingServer.destroy();
        assertTrue(Files.isDirectory(activeSnapshot));

        AppDeploymentLocalServer disabledServer = new AppDeploymentLocalServer(
                new AppDeploymentProperties(deploymentRoot, "https://sites.example.com"),
                new AppDeploymentLocalServerProperties(false, "127.0.0.1", 9332),
                previewOutputRoot,
                previewSnapshotRoot,
                clock,
                new SecureRandom(),
                new NioFileTreeOperations()
        );
        disabledServer.start();
        assertThrows(IllegalStateException.class, disabledServer::requirePreviewAvailable);
        disabledServer.destroy();
        assertTrue(Files.isDirectory(activeSnapshot));
    }

    @Test
    void rejectsHostPortDriftAndSkipsValidationWhenDisabled() {
        localServer = newLocalServer(
                "http://127.0.0.1:9444",
                new AppDeploymentLocalServerProperties(true, "127.0.0.1", 0)
        );
        IllegalStateException mismatch = assertThrows(IllegalStateException.class, localServer::start);
        assertTrue(mismatch.getMessage().contains("port must match"));
        assertFalse(localServer.isRunning());

        localServer = newLocalServer(
                "https://sites.example.com",
                new AppDeploymentLocalServerProperties(false, "127.0.0.1", 9332)
        );
        localServer.start();
        assertFalse(localServer.isRunning());
    }

    @Test
    void requiresPublicHostToResolveToTheConfiguredBindAddress() {
        localServer = newLocalServer(
                "http://192.0.2.1:0",
                new AppDeploymentLocalServerProperties(true, "127.0.0.1", 0)
        );
        IllegalStateException mismatch = assertThrows(IllegalStateException.class, localServer::start);
        assertTrue(mismatch.getMessage().contains("must resolve to"));
        assertFalse(localServer.isRunning());

        localServer = newLocalServer(
                "http://127.0.0.1:0",
                new AppDeploymentLocalServerProperties(true, "0.0.0.0", 0)
        );
        localServer.start();
        assertTrue(localServer.isRunning());
    }

    private Path createDeploymentFixture() throws IOException {
        Path keyDirectory = Files.createDirectories(deploymentRoot.resolve(DEPLOY_KEY));
        Files.writeString(keyDirectory.resolve("index.html"), INDEX, StandardCharsets.UTF_8);
        Files.writeString(keyDirectory.resolve("style.css"), STYLE, StandardCharsets.UTF_8);
        Files.writeString(keyDirectory.resolve("script.js"), SCRIPT, StandardCharsets.UTF_8);
        return keyDirectory;
    }

    private Path createPreviewFixture(CodeGenTypeEnum type, long appId) throws IOException {
        Path previewDirectory = Files.createDirectories(
                previewOutputRoot.resolve(type.getValue() + "_" + appId)
        );
        Files.writeString(previewDirectory.resolve("index.html"), INDEX, StandardCharsets.UTF_8);
        if (type == CodeGenTypeEnum.MULTI_FILE) {
            Files.writeString(previewDirectory.resolve("style.css"), STYLE, StandardCharsets.UTF_8);
            Files.writeString(previewDirectory.resolve("script.js"), SCRIPT, StandardCharsets.UTF_8);
        }
        return previewDirectory;
    }

    private static String previewToken(AppDeploymentLocalServer.PreviewAccess access) {
        String[] pathSegments = URI.create(access.url()).getPath().split("/");
        return pathSegments[2];
    }

    private PreviewSession bootstrap(AppDeploymentLocalServer.PreviewAccess access)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = request(
                "GET",
                URI.create(access.url()).getRawPath()
        );
        assertEquals(302, response.statusCode());
        assertPreviewSecurityHeaders(response);
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        String contentRoot = response.headers().firstValue("Location").orElseThrow();
        String[] contentSegments = URI.create(contentRoot).getPath().split("/");
        return new PreviewSession(
                contentRoot,
                contentSegments[2],
                setCookie,
                setCookie.substring(0, setCookie.indexOf(';'))
        );
    }

    private long countPreviewRootEntries() throws IOException {
        try (var entries = Files.list(previewSnapshotRoot)) {
            return entries.count();
        }
    }

    private void startServer() {
        localServer = newLocalServer(
                "http://127.0.0.1:0",
                new AppDeploymentLocalServerProperties(true, "127.0.0.1", 0)
        );
        localServer.start();
        serverOrigin = URI.create("http://127.0.0.1:" + localServer.getAddress().getPort());
    }

    private AppDeploymentLocalServer newLocalServer(
            String publicHost,
            AppDeploymentLocalServerProperties localServerProperties
    ) {
        return new AppDeploymentLocalServer(
                new AppDeploymentProperties(deploymentRoot, publicHost),
                localServerProperties,
                previewOutputRoot,
                previewSnapshotRoot,
                clock,
                new SecureRandom(),
                new NioFileTreeOperations()
        );
    }

    private HttpResponse<byte[]> request(String method, String rawPath) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(serverOrigin.resolve(rawPath))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> requestWithCookie(
            String method,
            String rawPath,
            String cookie
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(serverOrigin.resolve(rawPath))
                .header("Cookie", cookie)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assertSecurityHeaders(HttpResponse<?> response) {
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
        String contentSecurityPolicy = response.headers()
                .firstValue("Content-Security-Policy")
                .orElseThrow();
        assertEquals("sandbox allow-scripts", contentSecurityPolicy);
        assertFalse(contentSecurityPolicy.contains("allow-same-origin"));
        assertFalse(contentSecurityPolicy.contains("allow-forms"));
        assertFalse(contentSecurityPolicy.contains("allow-top-navigation"));
        assertEquals(
                "camera=(), microphone=(), geolocation=()",
                response.headers().firstValue("Permissions-Policy").orElseThrow()
        );
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
    }

    private static void assertPreviewSecurityHeaders(HttpResponse<?> response) {
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
        String contentSecurityPolicy = response.headers()
                .firstValue("Content-Security-Policy")
                .orElseThrow();
        assertTrue(contentSecurityPolicy.startsWith("sandbox allow-scripts"));
        assertTrue(contentSecurityPolicy.contains("connect-src 'none'"));
        assertTrue(contentSecurityPolicy.contains("img-src 'self' data: blob:"));
        assertTrue(contentSecurityPolicy.contains("style-src 'self' 'unsafe-inline' data:"));
        assertTrue(contentSecurityPolicy.contains("script-src 'self' 'unsafe-inline' blob:"));
        assertTrue(contentSecurityPolicy.contains("font-src 'self' data:"));
        assertTrue(contentSecurityPolicy.contains("media-src 'self' data: blob:"));
        assertTrue(contentSecurityPolicy.contains("form-action 'none'"));
        assertTrue(contentSecurityPolicy.contains("base-uri 'none'"));
        assertTrue(contentSecurityPolicy.contains("object-src 'none'"));
        assertTrue(contentSecurityPolicy.contains("frame-src 'none'"));
        assertFalse(contentSecurityPolicy.contains("allow-same-origin"));
        assertFalse(contentSecurityPolicy.contains("allow-forms"));
        assertFalse(contentSecurityPolicy.contains("allow-top-navigation"));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getClass().getSimpleName());
        }
    }

    private static final class MutableClock extends Clock {

        private Instant currentInstant;

        private final ZoneId zone;

        private MutableClock(Instant currentInstant, ZoneId zone) {
            this.currentInstant = currentInstant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(currentInstant, requestedZone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }

        private void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }
    }

    private record PreviewSession(
            String contentRoot,
            String publicId,
            String setCookie,
            String cookiePair
    ) {
    }
}
