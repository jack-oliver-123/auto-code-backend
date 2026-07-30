package com.jack.autocodebackend.core.deploy;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jack.autocodebackend.ai.AiCodeGeneratorService;
import com.jack.autocodebackend.config.AppDeploymentLocalServerProperties;
import com.jack.autocodebackend.config.AppDeploymentProperties;
import com.jack.autocodebackend.config.AppPreviewProperties;
import com.jack.autocodebackend.config.AppVueProjectProperties;
import com.jack.autocodebackend.core.AiCodeGeneratorFacade;
import com.jack.autocodebackend.core.lock.AppProcessingLeaseManager;
import com.jack.autocodebackend.core.saver.CodeFileSaverRegistry;
import com.jack.autocodebackend.core.saver.VueProjectCodeFileSaver;
import com.jack.autocodebackend.core.vue.VueDistValidator;
import com.jack.autocodebackend.core.vue.VueProjectBuilder;
import com.jack.autocodebackend.core.vue.VueProjectMaterializer;
import com.jack.autocodebackend.core.vue.VueProjectScaffold;
import com.jack.autocodebackend.core.vue.VueProjectSourceContextLoader;
import com.jack.autocodebackend.core.vue.VueProjectSourceValidator;
import com.jack.autocodebackend.mapper.AppMapper;
import com.jack.autocodebackend.model.domain.App;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.enums.AppGenerationStatusEnum;
import com.jack.autocodebackend.model.enums.ChatHistoryMessageTypeEnum;
import com.jack.autocodebackend.model.vo.AppGenerationEvent;
import com.jack.autocodebackend.model.vo.AppPreviewVO;
import com.jack.autocodebackend.service.ChatHistoryService;
import com.jack.autocodebackend.service.ChatMemoryService;
import com.jack.autocodebackend.service.impl.AppServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SuppressWarnings({"unchecked", "rawtypes"})
class VueProjectGenerationE2ETest {

    private static final long APP_ID = 9_223_372_036_854_775L;

    private static final long OWNER_ID = 1_001L;

    private static final String INITIAL_PROMPT = "Build an end-to-end project";

    @TempDir
    Path temporaryRoot;

    private AppDeploymentLocalServer localServer;

    @AfterEach
    void stopPreviewServer() {
        if (localServer != null) {
            localServer.destroy();
        }
    }

    @Test
    void streamedFixturePublishesPreviewPersistsHistoryAndCompletesLast() throws Exception {
        List<String> lifecycle = new CopyOnWriteArrayList<>();
        Path outputRoot = Files.createDirectory(temporaryRoot.resolve("code-output"));
        Path deploymentRoot = Files.createDirectory(temporaryRoot.resolve("deploy"));
        Path previewRoot = temporaryRoot.resolve("preview");
        AppVueProjectProperties vueProperties = AppVueProjectProperties.defaults();
        VueProjectScaffold scaffold = new VueProjectScaffold();
        VueProjectSourceValidator sourceValidator = new VueProjectSourceValidator(vueProperties);
        VueDistValidator distValidator = new VueDistValidator(vueProperties);
        VueProjectCodeFileSaver vueSaver = createSaver(
                sourceValidator,
                new VueProjectMaterializer(scaffold),
                scaffold,
                new FixtureBuilder(lifecycle),
                distValidator,
                outputRoot
        );

        String appSource = "<template><main id=\"e2e\">End to end project</main></template>\n";
        String completeResponse = envelope(appSource);
        List<String> chunks = List.of(
                completeResponse.substring(0, 31),
                completeResponse.substring(31, 127),
                completeResponse.substring(127)
        );
        AiCodeGeneratorService aiService = mock(AiCodeGeneratorService.class);
        given(aiService.generateVueProjectCodeStream(INITIAL_PROMPT))
                .willReturn(Flux.defer(() -> {
                    lifecycle.add("provider");
                    return Flux.fromIterable(chunks);
                }));
        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade(
                aiService,
                new CodeFileSaverRegistry(List.of(vueSaver)),
                vueProperties
        );
        ObjectProvider<AiCodeGeneratorFacade> facadeProvider = mock(ObjectProvider.class);
        given(facadeProvider.getIfAvailable()).willReturn(facade);

        AppDeploymentProperties deploymentProperties = new AppDeploymentProperties(
                deploymentRoot, "http://127.0.0.1:0");
        localServer = new AppDeploymentLocalServer(
                deploymentProperties,
                new AppPreviewProperties("http://127.0.0.1:0"),
                new AppDeploymentLocalServerProperties(true, "127.0.0.1", 0),
                outputRoot,
                previewRoot,
                Clock.systemUTC(),
                new SecureRandom(),
                new NioFileTreeOperations(),
                distValidator
        );
        localServer.start();

        App app = new App();
        app.setId(APP_ID);
        app.setUserId(OWNER_ID);
        app.setInitPrompt(INITIAL_PROMPT);
        app.setGenerationStatus(AppGenerationStatusEnum.PENDING.getValue());
        AppMapper appMapper = mock(AppMapper.class);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(appMapper.startGenerationAttempt(
                anyLong(), anyLong(), any(), any(), any(), any())).willAnswer(invocation -> {
                    lifecycle.add("status-generating");
                    return 1;
                });
        given(appMapper.completeGenerationAttempt(
                anyLong(), anyLong(), any(), any())).willAnswer(invocation -> {
                    lifecycle.add("status-succeeded");
                    return 1;
                });
        given(appMapper.update(any(App.class), any(Wrapper.class))).willAnswer(invocation -> {
            lifecycle.add("type");
            return 1;
        });

        AtomicLong historyIds = new AtomicLong(100);
        List<HistoryWrite> historyWrites = new CopyOnWriteArrayList<>();
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        given(historyService.addChatMessage(
                anyLong(), anyLong(), any(), any(ChatHistoryMessageTypeEnum.class)))
                .willAnswer(invocation -> {
                    ChatHistoryMessageTypeEnum type = invocation.getArgument(3);
                    String message = invocation.getArgument(2);
                    historyWrites.add(new HistoryWrite(type, message));
                    lifecycle.add(type == ChatHistoryMessageTypeEnum.USER
                            ? "history-user" : "history-ai");
                    return historyIds.incrementAndGet();
                });

        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        given(memoryService.buildPrompt(anyLong(), anyLong(), any(), anyBoolean()))
                .willAnswer(invocation -> invocation.getArgument(2));
        doAnswer(invocation -> {
            lifecycle.add("memory");
            return null;
        }).when(memoryService).refresh(APP_ID);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        AppServiceImpl appService = new AppServiceImpl(
                facadeProvider,
                mock(AppDeploymentFileManager.class),
                mock(DeployKeyGenerator.class),
                deploymentProperties,
                localServer,
                historyService,
                memoryService,
                new ImmediateLeaseManager(),
                transactionTemplate,
                mock(VueProjectSourceContextLoader.class)
        );
        ReflectionTestUtils.setField(appService, "baseMapper", appMapper);
        User owner = new User();
        owner.setId(OWNER_ID);

        List<AppGenerationEvent> events = appService.chatToGenCode(
                        APP_ID, "ignored initial message", owner)
                .doOnNext(event -> {
                    if (event instanceof AppGenerationEvent.Completed) {
                        lifecycle.add("done");
                    }
                })
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(events).isNotNull();
        assertThat(events.subList(0, chunks.size()))
                .containsExactlyElementsOf(chunks.stream()
                        .map(AppGenerationEvent.Content::new).toList());
        assertThat(events.getLast()).isInstanceOf(AppGenerationEvent.Completed.class);
        assertThat(historyWrites).containsExactly(
                new HistoryWrite(ChatHistoryMessageTypeEnum.USER, INITIAL_PROMPT),
                new HistoryWrite(ChatHistoryMessageTypeEnum.AI, completeResponse)
        );
        assertThat(lifecycle).containsSubsequence(
                "status-generating", "history-user", "provider", "build", "history-ai",
                "type", "status-succeeded", "memory", "done");
        assertThat(lifecycle.getLast()).isEqualTo("done");
        Path stable = outputRoot.resolve("vue_project_" + APP_ID);
        assertThat(stable.resolve("src/App.vue")).hasContent(appSource);
        assertThat(stable.resolve("dist/index.html")).isRegularFile();
        assertThat(stable.resolve("node_modules")).doesNotExist();

        AppPreviewVO preview = ((AppGenerationEvent.Completed) events.getLast()).preview();
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpResponse<String> bootstrap = client.send(
                HttpRequest.newBuilder(URI.create(preview.getPreviewUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(bootstrap.statusCode()).isEqualTo(302);
        String cookie = bootstrap.headers().firstValue("Set-Cookie").orElseThrow()
                .split(";", 2)[0];
        URI contentUri = URI.create(preview.getPreviewUrl()).resolve(
                bootstrap.headers().firstValue("Location").orElseThrow());
        HttpResponse<String> content = client.send(
                HttpRequest.newBuilder(contentUri).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(content.statusCode()).isEqualTo(200);
        assertThat(content.body()).contains("End to end project");
        verify(aiService).generateVueProjectCodeStream(INITIAL_PROMPT);
    }

    private static VueProjectCodeFileSaver createSaver(
            VueProjectSourceValidator sourceValidator,
            VueProjectMaterializer materializer,
            VueProjectScaffold scaffold,
            VueProjectBuilder builder,
            VueDistValidator distValidator,
            Path outputRoot
    ) throws Exception {
        Constructor<VueProjectCodeFileSaver> constructor =
                VueProjectCodeFileSaver.class.getDeclaredConstructor(
                        VueProjectSourceValidator.class,
                        VueProjectMaterializer.class,
                        VueProjectScaffold.class,
                        VueProjectBuilder.class,
                        VueDistValidator.class,
                        DirectoryPublisher.class,
                        Path.class
                );
        constructor.setAccessible(true);
        return constructor.newInstance(
                sourceValidator,
                materializer,
                scaffold,
                builder,
                distValidator,
                new DirectoryPublisher(),
                outputRoot
        );
    }

    private static String envelope(String appSource) {
        return "<<<AUTO_CODE_PROJECT_V1>>>\n"
                + "FILE: src/main.js\n```js\n"
                + "import { createApp } from 'vue'\n"
                + "import App from './App.vue'\n"
                + "createApp(App).mount('#app')\n```\n"
                + "FILE: src/App.vue\n```vue\n" + appSource + "```\n"
                + "FILE: src/router/index.js\n```js\n"
                + "import { createWebHashHistory } from 'vue-router'\n"
                + "export default createWebHashHistory()\n```\n"
                + "<<<END_AUTO_CODE_PROJECT_V1>>>";
    }

    private record HistoryWrite(ChatHistoryMessageTypeEnum type, String message) {
    }

    private static final class FixtureBuilder implements VueProjectBuilder {

        private final List<String> lifecycle;

        private FixtureBuilder(List<String> lifecycle) {
            this.lifecycle = lifecycle;
        }

        @Override
        public VueBuildResult build(long appId, Path projectDirectory) {
            lifecycle.add("build");
            try {
                Path assets = Files.createDirectories(projectDirectory.resolve("dist/assets"));
                Files.writeString(
                        projectDirectory.resolve("dist/index.html"),
                        "<!doctype html><div id=\"app\">End to end project</div>"
                                + "<script type=\"module\" src=\"./assets/app.js\"></script>"
                );
                Files.writeString(assets.resolve("app.js"), "console.log('built')");
                return new VueBuildResult(Duration.ZERO, 0);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static final class ImmediateLeaseManager implements AppProcessingLeaseManager {

        @Override
        public AppProcessingLease acquire(Long appId) {
            return new AppProcessingLease() {
                @Override
                public long appId() {
                    return appId;
                }

                @Override
                public boolean isLost() {
                    return false;
                }

                @Override
                public void assertHeld() {
                }

                @Override
                public Mono<Void> lossSignal() {
                    return Mono.never();
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
