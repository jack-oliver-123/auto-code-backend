package com.jack.autocodebackend.core.deploy;

import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.config.AppDeploymentLocalServerProperties;
import com.jack.autocodebackend.config.AppDeploymentProperties;
import com.jack.autocodebackend.config.AppPreviewProperties;
import com.jack.autocodebackend.config.AppVueProjectProperties;
import com.jack.autocodebackend.constant.AppConstant;
import com.jack.autocodebackend.core.deploy.GeneratedArtifactLayoutResolver.GeneratedArtifactLayout;
import com.jack.autocodebackend.core.vue.VueDistValidator;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.URLDecoder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static java.nio.file.FileVisitResult.CONTINUE;

@Component
public final class AppDeploymentLocalServer implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AppDeploymentLocalServer.class);

    private static final Pattern DEPLOY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9]{6}");

    private static final Pattern PREVIEW_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final String PREVIEW_PATH_SEGMENT = "preview";

    private static final String PREVIEW_CONTENT_PATH_SEGMENT = "preview-content";

    private static final String PREVIEW_COOKIE_NAME = "AUTO_CODE_PREVIEW";

    private static final int PREVIEW_TOKEN_BYTES = 32;

    private static final Duration PREVIEW_TTL = Duration.ofMinutes(15);

    private static final String DEPLOYMENT_CONTENT_SECURITY_POLICY =
            "sandbox allow-scripts";

    private static final String PREVIEW_CONTENT_SECURITY_POLICY =
            "sandbox allow-scripts; "
            + "default-src 'none'; connect-src 'none'; "
            + "img-src 'self' data: blob:; style-src 'self' 'unsafe-inline' data:; "
            + "script-src 'self' 'unsafe-inline' blob:; font-src 'self' data:; "
            + "media-src 'self' data: blob:; form-action 'none'; "
            + "base-uri 'none'; object-src 'none'; frame-src 'none'";

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Set<OpenOption> READ_WITHOUT_FOLLOWING_LINKS = Set.of(
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
    );

    private static final int NOT_FOUND = 404;

    private static final int METHOD_NOT_ALLOWED = 405;

    private final AppDeploymentProperties deploymentProperties;

    private final AppPreviewProperties previewProperties;

    private final AppDeploymentLocalServerProperties localServerProperties;

    private final Path generatedOutputRoot;

    private final Path previewSnapshotRoot;

    private final NioFileTreeOperations fileOperations;

    private final DirectoryPublisher snapshotPublisher;

    private final VueDistValidator vueDistValidator;

    private final Clock clock;

    private final SecureRandom secureRandom;

    private final Object lifecycleMonitor = new Object();

    private final Object previewAccessMonitor = new Object();

    private final Map<String, PreviewGrant> previewGrantsByDigest = new HashMap<>();

    private final Map<Long, String> previewDigestByAppId = new HashMap<>();

    private final Map<String, String> previewDigestByPublicId = new HashMap<>();

    private HttpServer server;

    private ExecutorService executor;

    private boolean previewSnapshotRootOwned;

    public AppDeploymentLocalServer(
            AppDeploymentProperties deploymentProperties,
            AppPreviewProperties previewProperties,
            AppDeploymentLocalServerProperties localServerProperties
    ) {
        this(
                deploymentProperties,
                previewProperties,
                localServerProperties,
                new VueDistValidator(AppVueProjectProperties.defaults())
        );
    }

    @Autowired
    public AppDeploymentLocalServer(
            AppDeploymentProperties deploymentProperties,
            AppPreviewProperties previewProperties,
            AppDeploymentLocalServerProperties localServerProperties,
            VueDistValidator vueDistValidator
    ) {
        this(
                deploymentProperties,
                previewProperties,
                localServerProperties,
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).resolveSibling("code_preview"),
                Clock.systemUTC(),
                new SecureRandom(),
                new NioFileTreeOperations(),
                vueDistValidator
        );
    }

    AppDeploymentLocalServer(
            AppDeploymentProperties deploymentProperties,
            AppPreviewProperties previewProperties,
            AppDeploymentLocalServerProperties localServerProperties,
            Path generatedOutputRoot,
            Path previewSnapshotRoot,
            Clock clock,
            SecureRandom secureRandom,
            NioFileTreeOperations fileOperations
    ) {
        this(
                deploymentProperties,
                previewProperties,
                localServerProperties,
                generatedOutputRoot,
                previewSnapshotRoot,
                clock,
                secureRandom,
                fileOperations,
                new VueDistValidator(AppVueProjectProperties.defaults())
        );
    }

    AppDeploymentLocalServer(
            AppDeploymentProperties deploymentProperties,
            AppPreviewProperties previewProperties,
            AppDeploymentLocalServerProperties localServerProperties,
            Path generatedOutputRoot,
            Path previewSnapshotRoot,
            Clock clock,
            SecureRandom secureRandom,
            NioFileTreeOperations fileOperations,
            VueDistValidator vueDistValidator
    ) {
        this.deploymentProperties = Objects.requireNonNull(deploymentProperties);
        this.previewProperties = Objects.requireNonNull(previewProperties);
        this.localServerProperties = Objects.requireNonNull(localServerProperties);
        this.generatedOutputRoot = Objects.requireNonNull(generatedOutputRoot)
                .toAbsolutePath()
                .normalize();
        this.previewSnapshotRoot = Objects.requireNonNull(previewSnapshotRoot)
                .toAbsolutePath()
                .normalize();
        this.clock = Objects.requireNonNull(clock);
        this.secureRandom = Objects.requireNonNull(secureRandom);
        this.fileOperations = Objects.requireNonNull(fileOperations);
        this.snapshotPublisher = new DirectoryPublisher(fileOperations);
        this.vueDistValidator = Objects.requireNonNull(vueDistValidator);
        requirePairwiseNonOverlappingRoots(
                deploymentProperties.getRootDir(),
                this.generatedOutputRoot,
                this.previewSnapshotRoot
        );
    }

    @EventListener
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        if (event.getApplicationContext().getServerNamespace() == null) {
            start();
        }
    }

    void start() {
        synchronized (lifecycleMonitor) {
            if (!localServerProperties.isEnabled() || server != null) {
                return;
            }
            InetAddress bindAddress = resolveBindAddress();
            validatePublicHostMatchesListener(
                    deploymentProperties.getHost(),
                    "deployment",
                    bindAddress
            );
            validatePublicHostMatchesListener(
                    previewProperties.getHost(),
                    "preview",
                    bindAddress
            );

            HttpServer newServer = null;
            ExecutorService newExecutor = null;
            try {
                newServer = HttpServer.create(
                        new InetSocketAddress(bindAddress, localServerProperties.getPort()),
                        0
                );
                preparePreviewSnapshotRoot(true);
                newServer.createContext("/", this::handle);
                newExecutor = Executors.newVirtualThreadPerTaskExecutor();
                newServer.setExecutor(newExecutor);
                newServer.start();
                server = newServer;
                executor = newExecutor;
                log.info(
                        "Deployment local server listening on http://{}:{}",
                        server.getAddress().getAddress().getHostAddress(),
                        server.getAddress().getPort()
                );
            } catch (IOException | RuntimeException exception) {
                if (newServer != null) {
                    newServer.stop(0);
                }
                if (newExecutor != null) {
                    newExecutor.shutdownNow();
                }
                throw new IllegalStateException("failed to start deployment local server", exception);
            }
        }
    }

    boolean isRunning() {
        synchronized (lifecycleMonitor) {
            return server != null;
        }
    }

    InetSocketAddress getAddress() {
        synchronized (lifecycleMonitor) {
            if (server == null) {
                throw new IllegalStateException("deployment local server is not running");
            }
            return server.getAddress();
        }
    }

    public void requirePreviewAvailable() {
        synchronized (lifecycleMonitor) {
            requirePreviewAvailableLocked();
        }
    }

    public PreviewAccess issuePreview(long appId, CodeGenTypeEnum type) {
        try (PreviewPublication publication = preparePreview(appId, type)) {
            PreviewAccess access = publication.access();
            publication.commit();
            return access;
        }
    }

    public PreviewPublication preparePreview(long appId, CodeGenTypeEnum type) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        Objects.requireNonNull(type, "code generation type must not be null");

        synchronized (lifecycleMonitor) {
            requirePreviewAvailableLocked();
            purgeExpiredPreviewGrants();
            Path source = requireCompletePreviewSource(appId, type);
            SnapshotIdentity identity = allocateSnapshotIdentity();
            Path snapshot = publishPreviewSnapshot(source, type, identity.publicId());

            Instant expiresAt = clock.instant().plus(PREVIEW_TTL);
            PreviewInstallation installation;
            try {
                synchronized (previewAccessMonitor) {
                    PreviewGrant newGrant = new PreviewGrant(
                            appId,
                            type,
                            identity.publicId(),
                            snapshot,
                            expiresAt
                    );
                    installation = installPreviewGrant(identity.tokenDigest(), newGrant);
                }
            } catch (RuntimeException mapFailure) {
                deletePreviewSnapshot(snapshot);
                throw mapFailure;
            }

            PreviewAccess access = new PreviewAccess(
                    buildPreviewUrl(identity.token(), server.getAddress().getPort()), expiresAt
            );
            return new PreviewPublication(this, access, installation);
        }
    }

    public void revokePreview(long appId) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        PreviewGrant revokedGrant;
        synchronized (previewAccessMonitor) {
            revokedGrant = removePreviewGrantByAppId(appId);
        }
        if (revokedGrant != null) {
            deletePreviewSnapshot(revokedGrant.snapshotDirectory());
        }
    }

    @Override
    public void destroy() {
        synchronized (lifecycleMonitor) {
            if (server != null) {
                server.stop(0);
                server = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            synchronized (previewAccessMonitor) {
                previewGrantsByDigest.clear();
                previewDigestByAppId.clear();
                previewDigestByPublicId.clear();
            }
            if (previewSnapshotRootOwned) {
                cleanupPreviewSnapshotRoot();
                previewSnapshotRootOwned = false;
            }
        }
    }

    private void requirePreviewAvailableLocked() {
        if (!localServerProperties.isEnabled() || server == null) {
            throw new IllegalStateException("deployment local server is not running");
        }
    }

    private InetAddress resolveBindAddress() {
        try {
            return InetAddress.getByName(localServerProperties.getBindAddress());
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("deployment local server bind address cannot be resolved", exception);
        }
    }

    private void validatePublicHostMatchesListener(
            String configuredHost,
            String hostType,
            InetAddress bindAddress
    ) {
        URI publicHost;
        try {
            publicHost = URI.create(configuredHost);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(hostType + " host must be a valid HTTP origin", exception);
        }

        String path = publicHost.getRawPath();
        boolean hasPath = path != null && !path.isEmpty() && !"/".equals(path);
        if (!"http".equalsIgnoreCase(publicHost.getScheme())
                || publicHost.getHost() == null
                || publicHost.getRawUserInfo() != null
                || hasPath
                || publicHost.getRawQuery() != null
                || publicHost.getRawFragment() != null) {
            throw new IllegalStateException(
                    hostType + " host must be an HTTP origin while the local server is enabled"
            );
        }

        int publicPort = publicHost.getPort() == -1 ? 80 : publicHost.getPort();
        if (publicPort != localServerProperties.getPort()) {
            throw new IllegalStateException(
                    hostType + " host port must match the deployment local server port"
            );
        }

        InetAddress[] publicAddresses;
        try {
            publicAddresses = InetAddress.getAllByName(publicHost.getHost());
        } catch (UnknownHostException exception) {
            throw new IllegalStateException(hostType + " host cannot be resolved", exception);
        }
        if (!bindAddress.isAnyLocalAddress()
                && List.of(publicAddresses).stream().noneMatch(bindAddress::equals)) {
            throw new IllegalStateException(
                    hostType + " host must resolve to the deployment local server bind address"
            );
        }
    }

    private void handle(HttpExchange exchange) {
        try {
            String rawPath = exchange.getRequestURI().getRawPath();
            addSecurityHeaders(
                    exchange.getResponseHeaders(),
                    isPreviewRequestPath(rawPath)
            );
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                sendEmpty(exchange, METHOD_NOT_ALLOWED);
                return;
            }

            Optional<ResolvedRequest> resolvedRequest = resolve(rawPath, exchange.getRequestHeaders());
            if (resolvedRequest.isEmpty()) {
                sendEmpty(exchange, NOT_FOUND);
                return;
            }

            ResolvedRequest request = resolvedRequest.get();
            if (request.redirectLocation() != null) {
                exchange.getResponseHeaders().set("Location", request.redirectLocation());
                if (request.setCookie() != null) {
                    exchange.getResponseHeaders().add("Set-Cookie", request.setCookie());
                }
                sendEmpty(exchange, request.redirectStatus());
                return;
            }

            serveFile(exchange, request.file(), "HEAD".equals(method));
        } catch (RuntimeException exception) {
            log.debug("Deployment local server rejected a request ({})", exception.getClass().getSimpleName());
            try {
                sendEmpty(exchange, NOT_FOUND);
            } catch (IOException ignored) {
                // The connection may already be closed by the client.
            }
        } catch (IOException exception) {
            log.debug("Deployment local server could not complete a response ({})", exception.getClass().getSimpleName());
        } finally {
            exchange.close();
        }
    }

    private Optional<ResolvedRequest> resolve(String rawPath, Headers requestHeaders) {
        Optional<ParsedPath> parsedPath = parsePath(rawPath);
        if (parsedPath.isEmpty()) {
            return Optional.empty();
        }

        ParsedPath requestPath = parsedPath.get();
        if (PREVIEW_PATH_SEGMENT.equals(requestPath.segments().getFirst())) {
            return resolvePreviewBootstrap(requestPath);
        }
        if (PREVIEW_CONTENT_PATH_SEGMENT.equals(requestPath.segments().getFirst())) {
            return resolvePreviewContent(requestPath, requestHeaders);
        }
        return resolveDeployment(requestPath);
    }

    private Optional<ResolvedRequest> resolveDeployment(ParsedPath requestPath) {
        if (!DEPLOY_KEY_PATTERN.matcher(requestPath.segments().getFirst()).matches()
                || (requestPath.trailingSlash() && requestPath.segments().size() > 1)) {
            return Optional.empty();
        }

        Path root = deploymentProperties.getRootDir();
        if (!isSafeDirectory(root, root)) {
            return Optional.empty();
        }

        String deployKey = requestPath.segments().getFirst();
        Path keyDirectory;
        try {
            keyDirectory = root.resolve(deployKey).normalize();
        } catch (InvalidPathException exception) {
            return Optional.empty();
        }
        if (!keyDirectory.startsWith(root) || !isSafeDirectory(root, keyDirectory)) {
            return Optional.empty();
        }

        if (requestPath.segments().size() == 1 && !requestPath.trailingSlash()) {
            return Optional.of(ResolvedRequest.redirect("/" + deployKey + "/"));
        }

        List<String> relativeSegments = requestPath.segments().subList(1, requestPath.segments().size());
        return resolveFile(keyDirectory, relativeSegments);
    }

    private Optional<ResolvedRequest> resolvePreviewBootstrap(ParsedPath requestPath) {
        if (requestPath.segments().size() != 2) {
            return Optional.empty();
        }

        String token = requestPath.segments().get(1);
        if (!PREVIEW_TOKEN_PATTERN.matcher(token).matches()) {
            return Optional.empty();
        }
        Optional<PreviewGrant> grant = findPreviewGrant(token);
        if (grant.isEmpty()) {
            return Optional.empty();
        }

        if (!requestPath.trailingSlash()) {
            return Optional.of(ResolvedRequest.redirect(
                    "/" + PREVIEW_PATH_SEGMENT + "/" + token + "/",
                    308,
                    null
            ));
        }

        PreviewGrant previewGrant = grant.get();
        String contentRoot = "/" + PREVIEW_CONTENT_PATH_SEGMENT + "/"
                + previewGrant.publicId() + "/";
        return Optional.of(ResolvedRequest.redirect(
                contentRoot,
                302,
                buildPreviewCookie(token, previewGrant, contentRoot)
        ));
    }

    private Optional<ResolvedRequest> resolvePreviewContent(
            ParsedPath requestPath,
            Headers requestHeaders
    ) {
        if (requestPath.segments().size() < 2
                || (requestPath.trailingSlash() && requestPath.segments().size() > 2)) {
            return Optional.empty();
        }

        String publicId = requestPath.segments().get(1);
        if (!PREVIEW_TOKEN_PATTERN.matcher(publicId).matches()) {
            return Optional.empty();
        }
        Optional<String> token = findPreviewCookie(requestHeaders);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        Optional<PreviewGrant> grant = findPreviewGrant(token.get(), publicId);
        if (grant.isEmpty()) {
            return Optional.empty();
        }

        PreviewGrant previewGrant = grant.get();
        Path previewDirectory = previewGrant.snapshotDirectory();
        if (!isSafeSnapshotDirectory(previewDirectory)) {
            return Optional.empty();
        }

        if (requestPath.segments().size() == 2 && !requestPath.trailingSlash()) {
            return Optional.of(ResolvedRequest.redirect(
                    "/" + PREVIEW_CONTENT_PATH_SEGMENT + "/" + publicId + "/",
                    308,
                    null
            ));
        }

        List<String> relativeSegments = requestPath.segments().subList(
                2,
                requestPath.segments().size()
        );
        return resolveFile(previewDirectory, relativeSegments);
    }

    private Optional<ResolvedRequest> resolveFile(
            Path contentDirectory,
            List<String> relativeSegments
    ) {
        Path file = contentDirectory;
        if (relativeSegments.isEmpty()) {
            file = file.resolve("index.html");
        } else {
            for (int index = 0; index < relativeSegments.size(); index++) {
                try {
                    file = file.resolve(relativeSegments.get(index)).normalize();
                } catch (InvalidPathException exception) {
                    return Optional.empty();
                }
                if (!file.startsWith(contentDirectory) || isSymbolicOrHidden(file)) {
                    return Optional.empty();
                }
                if (index < relativeSegments.size() - 1 && !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    return Optional.empty();
                }
            }
        }

        if (!file.startsWith(contentDirectory)
                || isSymbolicOrHidden(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(ResolvedRequest.file(file));
    }

    private Optional<ParsedPath> parsePath(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/") || rawPath.length() <= 1) {
            return Optional.empty();
        }

        String[] rawSegments = rawPath.substring(1).split("/", -1);
        boolean trailingSlash = rawSegments[rawSegments.length - 1].isEmpty();
        int segmentCount = trailingSlash ? rawSegments.length - 1 : rawSegments.length;
        if (segmentCount == 0) {
            return Optional.empty();
        }

        List<String> segments = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            String rawSegment = rawSegments[index];
            if (rawSegment.isEmpty()) {
                return Optional.empty();
            }
            String decodedSegment;
            try {
                decodedSegment = URLDecoder.decode(rawSegment.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
            if (!isSafeSegment(decodedSegment)) {
                return Optional.empty();
            }
            segments.add(decodedSegment);
        }

        return Optional.of(new ParsedPath(List.copyOf(segments), trailingSlash));
    }

    private boolean isSafeDirectory(Path root, Path directory) {
        return directory.startsWith(root)
                && !isSymbolicOrHidden(directory)
                && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS);
    }

    private Path requireCompletePreviewSource(long appId, CodeGenTypeEnum type) {
        GeneratedArtifactLayout layout;
        try {
            layout = GeneratedArtifactLayoutResolver.resolve(generatedOutputRoot, type, appId);
        } catch (InvalidPathException exception) {
            throw new IllegalStateException("generated preview source path is invalid", exception);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("generated preview layout is invalid", exception);
        }
        Path projectRoot = layout.projectRoot();
        Path source = layout.staticRoot();
        if (!isSafeDirectory(generatedOutputRoot, generatedOutputRoot)
                || !projectRoot.startsWith(generatedOutputRoot)
                || !isSafeDirectory(generatedOutputRoot, projectRoot)
                || !source.startsWith(generatedOutputRoot)
                || !isSafeDirectory(generatedOutputRoot, source)) {
            throw new IllegalStateException("generated preview source is not a safe directory");
        }

        if (type == CodeGenTypeEnum.VUE_PROJECT) {
            try {
                vueDistValidator.validateProjectDist(projectRoot);
            } catch (IOException exception) {
                throw new IllegalStateException("generated Vue preview output is invalid", exception);
            }
        }
        validatePreviewTree(source);
        validateRequiredPreviewFiles(source, type);
        return source;
    }

    private Path publishPreviewSnapshot(
            Path source,
            CodeGenTypeEnum type,
            String publicId
    ) {
        preparePreviewSnapshotRoot(false);
        Path staging = null;
        try {
            staging = fileOperations.createTempDirectory(
                    previewSnapshotRoot,
                    "preview-staging-"
            ).toAbsolutePath().normalize();
            requireDirectPreviewRootChild(staging);
            fileOperations.copyRegularTree(source, staging);
            fileOperations.validateRegularTree(staging);
            validatePreviewTree(staging);
            validateRequiredPreviewFiles(staging, type);
            if (type == CodeGenTypeEnum.MULTI_FILE) {
                PreviewSnapshotBundler.bundle(staging);
            }

            Path target = previewSnapshotRoot.resolve(publicId).normalize();
            requireDirectPreviewRootChild(target);
            try (DirectoryPublisher.PublishedDirectory publication =
                         snapshotPublisher.publishNew(staging, target)) {
                publication.commit();
            }
            return target;
        } catch (IOException | RuntimeException exception) {
            if (staging != null && fileOperations.existsNoFollow(staging)) {
                try {
                    deleteContainedPreviewPath(staging);
                } catch (IOException | RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            if (exception instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("failed to create immutable preview snapshot", exception);
        }
    }

    private void validateRequiredPreviewFiles(Path directory, CodeGenTypeEnum type) {
        for (String requiredFileName : requiredPreviewFiles(type)) {
            Path requiredFile = directory.resolve(requiredFileName).normalize();
            if (!requiredFile.startsWith(directory)
                    || isSymbolicOrHidden(requiredFile)
                    || !Files.isRegularFile(requiredFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(
                        "generated preview source is incomplete: " + requiredFileName
                );
            }
        }
    }

    private void validatePreviewTree(Path source) {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(
                        Path directory,
                        BasicFileAttributes attributes
                ) throws IOException {
                    if (!directory.startsWith(source)
                            || attributes.isSymbolicLink()
                            || !attributes.isDirectory()
                            || isSymbolicOrHidden(directory)) {
                        throw new IOException("unsafe directory in generated preview source");
                    }
                    return CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes
                ) throws IOException {
                    if (!file.startsWith(source)
                            || attributes.isSymbolicLink()
                            || !attributes.isRegularFile()
                            || isSymbolicOrHidden(file)) {
                        throw new IOException("unsafe file in generated preview source");
                    }
                    return CONTINUE;
                }
            });
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("generated preview source contains unsafe content", exception);
        }
    }

    private static List<String> requiredPreviewFiles(CodeGenTypeEnum type) {
        return type.getRequiredStaticFiles();
    }

    private Optional<PreviewGrant> findPreviewGrant(String token) {
        return findPreviewGrant(token, null);
    }

    private Optional<PreviewGrant> findPreviewGrant(String token, String expectedPublicId) {
        String tokenDigest = digestPreviewToken(token);
        PreviewGrant expiredGrant = null;
        PreviewGrant result = null;
        synchronized (previewAccessMonitor) {
            Instant now = clock.instant();
            PreviewGrant grant = previewGrantsByDigest.get(tokenDigest);
            if (grant == null) {
                return Optional.empty();
            }
            boolean currentGrant = Objects.equals(
                    previewDigestByAppId.get(grant.appId()),
                    tokenDigest
            ) && Objects.equals(
                    previewDigestByPublicId.get(grant.publicId()),
                    tokenDigest
            );
            if (!now.isBefore(grant.expiresAt())) {
                expiredGrant = removePreviewGrant(tokenDigest);
            } else if (currentGrant
                    && (expectedPublicId == null || expectedPublicId.equals(grant.publicId()))) {
                result = grant;
            }
        }
        if (expiredGrant != null) {
            deletePreviewSnapshot(expiredGrant.snapshotDirectory());
        }
        return Optional.ofNullable(result);
    }

    private PreviewGrant removePreviewGrantByAppId(long appId) {
        String tokenDigest = previewDigestByAppId.get(appId);
        if (tokenDigest == null) {
            return null;
        }
        return removePreviewGrant(tokenDigest);
    }

    private PreviewInstallation installPreviewGrant(
            String tokenDigest,
            PreviewGrant newGrant
    ) {
        String previousDigest = previewDigestByAppId.get(newGrant.appId());
        PreviewGrant previousGrant = previousDigest == null
                ? null
                : previewGrantsByDigest.get(previousDigest);
        try {
            previewGrantsByDigest.put(tokenDigest, newGrant);
            previewDigestByPublicId.put(newGrant.publicId(), tokenDigest);
            previewDigestByAppId.put(newGrant.appId(), tokenDigest);
        } catch (RuntimeException installFailure) {
            previewGrantsByDigest.remove(tokenDigest);
            previewDigestByPublicId.remove(newGrant.publicId(), tokenDigest);
            previewDigestByAppId.remove(newGrant.appId(), tokenDigest);
            if (previousDigest != null) {
                previewDigestByAppId.put(newGrant.appId(), previousDigest);
            }
            throw installFailure;
        }
        if (previousDigest != null) {
            previewGrantsByDigest.remove(previousDigest);
            if (previousGrant != null) {
                previewDigestByPublicId.remove(previousGrant.publicId(), previousDigest);
            }
        }
        return new PreviewInstallation(
                tokenDigest,
                newGrant,
                previousDigest,
                previousGrant
        );
    }

    private void commitPreview(PreviewInstallation installation) {
        PreviewGrant previousGrant = installation.previousGrant();
        if (previousGrant != null) {
            deletePreviewSnapshot(previousGrant.snapshotDirectory());
        }
    }

    private void rollbackPreview(PreviewInstallation installation) {
        synchronized (previewAccessMonitor) {
            String currentDigest = previewDigestByAppId.get(installation.newGrant().appId());
            if (!installation.newDigest().equals(currentDigest)) {
                throw new IllegalStateException("preview publication is no longer current");
            }
            removePreviewGrant(installation.newDigest());
            if (installation.previousGrant() != null) {
                previewGrantsByDigest.put(
                        installation.previousDigest(), installation.previousGrant());
                previewDigestByAppId.put(
                        installation.previousGrant().appId(), installation.previousDigest());
                previewDigestByPublicId.put(
                        installation.previousGrant().publicId(), installation.previousDigest());
            }
        }
        deletePreviewSnapshot(installation.newGrant().snapshotDirectory());
    }

    private PreviewGrant removePreviewGrant(String tokenDigest) {
        PreviewGrant grant = previewGrantsByDigest.remove(tokenDigest);
        if (grant != null) {
            previewDigestByAppId.remove(grant.appId(), tokenDigest);
            previewDigestByPublicId.remove(grant.publicId(), tokenDigest);
        }
        return grant;
    }

    private void purgeExpiredPreviewGrants() {
        List<Path> expiredSnapshots = new ArrayList<>();
        Instant now = clock.instant();
        synchronized (previewAccessMonitor) {
            Iterator<Map.Entry<String, PreviewGrant>> iterator =
                    previewGrantsByDigest.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, PreviewGrant> entry = iterator.next();
                PreviewGrant grant = entry.getValue();
                if (!now.isBefore(grant.expiresAt())) {
                    iterator.remove();
                    previewDigestByAppId.remove(grant.appId(), entry.getKey());
                    previewDigestByPublicId.remove(grant.publicId(), entry.getKey());
                    expiredSnapshots.add(grant.snapshotDirectory());
                }
            }
        }
        expiredSnapshots.forEach(this::deletePreviewSnapshot);
    }

    private SnapshotIdentity allocateSnapshotIdentity() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String token = generatePreviewIdentifier();
            String tokenDigest = digestPreviewToken(token);
            String publicId = generatePreviewIdentifier();
            Path snapshot = previewSnapshotRoot.resolve(publicId).normalize();
            requireDirectPreviewRootChild(snapshot);
            synchronized (previewAccessMonitor) {
                if (!previewGrantsByDigest.containsKey(tokenDigest)
                        && !previewDigestByPublicId.containsKey(publicId)
                        && !fileOperations.existsNoFollow(snapshot)) {
                    return new SnapshotIdentity(token, tokenDigest, publicId);
                }
            }
        }
        throw new IllegalStateException("unable to allocate preview identifiers");
    }

    private String generatePreviewIdentifier() {
        byte[] identifierBytes = new byte[PREVIEW_TOKEN_BYTES];
        secureRandom.nextBytes(identifierBytes);
        return BASE64_URL_ENCODER.encodeToString(identifierBytes);
    }

    private Optional<String> findPreviewCookie(Headers requestHeaders) {
        String token = null;
        for (String cookieHeader : requestHeaders.getOrDefault("Cookie", List.of())) {
            for (String cookiePart : cookieHeader.split(";")) {
                String trimmedPart = cookiePart.trim();
                int separator = trimmedPart.indexOf('=');
                if (separator <= 0
                        || !PREVIEW_COOKIE_NAME.equals(trimmedPart.substring(0, separator))) {
                    continue;
                }
                String candidate = trimmedPart.substring(separator + 1);
                if (!PREVIEW_TOKEN_PATTERN.matcher(candidate).matches()) {
                    return Optional.empty();
                }
                if (token != null && !token.equals(candidate)) {
                    return Optional.empty();
                }
                token = candidate;
            }
        }
        return Optional.ofNullable(token);
    }

    private String buildPreviewCookie(
            String token,
            PreviewGrant grant,
            String contentRoot
    ) {
        long remainingMillis = Duration.between(clock.instant(), grant.expiresAt()).toMillis();
        long maxAgeSeconds = Math.max(1L, Math.floorDiv(remainingMillis + 999L, 1000L));
        return PREVIEW_COOKIE_NAME + "=" + token
                + "; Path=" + contentRoot
                + "; Max-Age=" + maxAgeSeconds
                + "; HttpOnly; SameSite=Strict";
    }

    private boolean isSafeSnapshotDirectory(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        return normalized.getParent() != null
                && normalized.getParent().equals(previewSnapshotRoot)
                && PREVIEW_TOKEN_PATTERN.matcher(normalized.getFileName().toString()).matches()
                && isSafeDirectory(previewSnapshotRoot, previewSnapshotRoot)
                && isSafeDirectory(previewSnapshotRoot, normalized);
    }

    private void preparePreviewSnapshotRoot(boolean cleanupExistingContent) {
        try {
            fileOperations.createDirectories(previewSnapshotRoot);
            if (!isSafeDirectory(previewSnapshotRoot, previewSnapshotRoot)) {
                throw new IOException("preview snapshot root is not a safe directory");
            }
            previewSnapshotRootOwned = true;
            if (cleanupExistingContent) {
                cleanupPreviewSnapshotRootContents();
            }
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("failed to prepare preview snapshot root", exception);
        }
    }

    private void cleanupPreviewSnapshotRoot() {
        if (!fileOperations.existsNoFollow(previewSnapshotRoot)) {
            return;
        }
        try {
            if (!isSafeDirectory(previewSnapshotRoot, previewSnapshotRoot)) {
                throw new IOException("preview snapshot root is not a safe directory");
            }
            cleanupPreviewSnapshotRootContents();
        } catch (IOException | RuntimeException exception) {
            log.warn("Preview snapshot cleanup failed: {}", previewSnapshotRoot, exception);
        }
    }

    private void cleanupPreviewSnapshotRootContents() throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(previewSnapshotRoot)) {
            for (Path child : children) {
                deleteContainedPreviewPath(child);
            }
        }
    }

    private void deletePreviewSnapshot(Path snapshot) {
        try {
            deleteContainedPreviewPath(snapshot);
        } catch (IOException | RuntimeException exception) {
            log.warn("Preview snapshot deletion failed: {}", snapshot, exception);
        }
    }

    private void deleteContainedPreviewPath(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        requireDirectPreviewRootChild(normalized);
        fileOperations.deleteTree(normalized);
    }

    private void requireDirectPreviewRootChild(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(previewSnapshotRoot)
                || normalized.getParent() == null
                || !normalized.getParent().equals(previewSnapshotRoot)) {
            throw new IllegalArgumentException("preview path escapes the snapshot root");
        }
    }

    private String buildPreviewUrl(String token, int listenerPort) {
        URI configuredHost = URI.create(previewProperties.getHost());
        try {
            return new URI(
                    configuredHost.getScheme(),
                    null,
                    configuredHost.getHost(),
                    listenerPort,
                    "/" + PREVIEW_PATH_SEGMENT + "/" + token + "/",
                    null,
                    null
            ).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("failed to build preview URL", exception);
        }
    }

    private static String digestPreviewToken(String token) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return BASE64_URL_ENCODER.encodeToString(
                    messageDigest.digest(token.getBytes(StandardCharsets.US_ASCII))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requirePairwiseNonOverlappingRoots(Path... roots) {
        for (int left = 0; left < roots.length; left++) {
            for (int right = left + 1; right < roots.length; right++) {
                if (roots[left].startsWith(roots[right])
                        || roots[right].startsWith(roots[left])) {
                    throw new IllegalArgumentException("preview filesystem roots must not overlap");
                }
            }
        }
    }

    private boolean isSymbolicOrHidden(Path path) {
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        try {
            return Files.isHidden(path);
        } catch (IOException | SecurityException exception) {
            return true;
        }
    }

    private static boolean isSafeSegment(String segment) {
        if (segment.isEmpty()
                || ".".equals(segment)
                || "..".equals(segment)
                || segment.startsWith(".")
                || segment.indexOf('/') >= 0
                || segment.indexOf('\\') >= 0) {
            return false;
        }
        return segment.codePoints().noneMatch(Character::isISOControl);
    }

    private void serveFile(HttpExchange exchange, Path file, boolean headOnly) throws IOException {
        boolean responseCommitted = false;
        try (SeekableByteChannel channel = Files.newByteChannel(file, READ_WITHOUT_FOLLOWING_LINKS)) {
            long size = channel.size();
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType(file));
            if (headOnly || size == 0) {
                headers.set("Content-Length", Long.toString(size));
                exchange.sendResponseHeaders(200, -1);
                responseCommitted = true;
                return;
            }

            exchange.sendResponseHeaders(200, size);
            responseCommitted = true;
            try (OutputStream responseBody = exchange.getResponseBody()) {
                Channels.newInputStream(channel).transferTo(responseBody);
            }
        } catch (IOException | RuntimeException exception) {
            if (!responseCommitted) {
                sendEmpty(exchange, NOT_FOUND);
                return;
            }
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private static String contentType(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int extensionStart = fileName.lastIndexOf('.');
        String extension = extensionStart == -1 ? "" : fileName.substring(extensionStart + 1);
        return switch (extension) {
            case "html", "htm" -> "text/html; charset=UTF-8";
            case "css" -> "text/css; charset=UTF-8";
            case "js", "mjs" -> "text/javascript; charset=UTF-8";
            case "json", "map" -> "application/json; charset=UTF-8";
            case "txt" -> "text/plain; charset=UTF-8";
            case "xml" -> "application/xml; charset=UTF-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "wasm" -> "application/wasm";
            default -> "application/octet-stream";
        };
    }

    private static boolean isPreviewRequestPath(String rawPath) {
        return rawPath != null && (rawPath.equals("/" + PREVIEW_PATH_SEGMENT)
                || rawPath.startsWith("/" + PREVIEW_PATH_SEGMENT + "/")
                || rawPath.equals("/" + PREVIEW_CONTENT_PATH_SEGMENT)
                || rawPath.startsWith("/" + PREVIEW_CONTENT_PATH_SEGMENT + "/"));
    }

    private static void addSecurityHeaders(Headers headers, boolean previewRequest) {
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set(
                "Content-Security-Policy",
                previewRequest
                        ? PREVIEW_CONTENT_SECURITY_POLICY
                        : DEPLOYMENT_CONTENT_SECURITY_POLICY
        );
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private record ParsedPath(List<String> segments, boolean trailingSlash) {
    }

    public record PreviewAccess(String url, Instant expiresAt) {

        public PreviewAccess {
            Objects.requireNonNull(url);
            Objects.requireNonNull(expiresAt);
        }
    }

    public static final class PreviewPublication implements AutoCloseable {

        private final AppDeploymentLocalServer server;
        private final PreviewAccess access;
        private final PreviewInstallation installation;
        private boolean resolved;

        private PreviewPublication(
                AppDeploymentLocalServer server,
                PreviewAccess access,
                PreviewInstallation installation
        ) {
            this.server = server;
            this.access = access;
            this.installation = installation;
        }

        public PreviewAccess access() {
            return access;
        }

        public synchronized void commit() {
            requireActive();
            server.commitPreview(installation);
            resolved = true;
        }

        public synchronized void rollback() {
            requireActive();
            server.rollbackPreview(installation);
            resolved = true;
        }

        @Override
        public synchronized void close() {
            if (!resolved) {
                rollback();
            }
        }

        private void requireActive() {
            if (resolved) {
                throw new IllegalStateException("preview publication is already resolved");
            }
        }
    }

    private record PreviewGrant(
            long appId,
            CodeGenTypeEnum type,
            String publicId,
            Path snapshotDirectory,
            Instant expiresAt
    ) {
    }

    private record PreviewInstallation(
            String newDigest,
            PreviewGrant newGrant,
            String previousDigest,
            PreviewGrant previousGrant
    ) {
    }

    private record SnapshotIdentity(String token, String tokenDigest, String publicId) {
    }

    private record ResolvedRequest(
            Path file,
            String redirectLocation,
            int redirectStatus,
            String setCookie
    ) {

        private static ResolvedRequest file(Path file) {
            return new ResolvedRequest(file, null, 0, null);
        }

        private static ResolvedRequest redirect(String location) {
            return redirect(location, 308, null);
        }

        private static ResolvedRequest redirect(
                String location,
                int status,
                String setCookie
        ) {
            return new ResolvedRequest(null, location, status, setCookie);
        }
    }
}
