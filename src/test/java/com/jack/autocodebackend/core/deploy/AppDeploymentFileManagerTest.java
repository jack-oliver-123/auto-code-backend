package com.jack.autocodebackend.core.deploy;

import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.exception.BusinessException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppDeploymentFileManagerTest {

    private static final long APP_ID = 101L;
    private static final String DEPLOY_KEY = "Ab3xY9";

    @TempDir
    Path tempDirectory;

    @Test
    void stagesAndPublishesHtmlSnapshot() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        Path source = sourceDirectory(outputRoot, CodeGenTypeEnum.HTML, APP_ID);
        write(source.resolve("index.html"), "<html>first</html>");
        write(source.resolve("assets/logo.txt"), "logo");
        AppDeploymentFileManager manager = manager(outputRoot, deploymentRoot);

        try (var staged = manager.stage(CodeGenTypeEnum.HTML, APP_ID);
             var published = staged.publishNew(DEPLOY_KEY)) {
            Path target = deploymentRoot.resolve(DEPLOY_KEY);
            assertAll(
                    () -> assertEquals("<html>first</html>", read(target.resolve("index.html"))),
                    () -> assertEquals("logo", read(target.resolve("assets/logo.txt"))),
                    () -> assertEquals("<html>first</html>", read(source.resolve("index.html")))
            );
            published.commit();
        }

        assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty());
    }

    @Test
    void stagesEveryRequiredMultiFileAsset() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        Path source = sourceDirectory(outputRoot, CodeGenTypeEnum.MULTI_FILE, APP_ID);
        write(source.resolve("index.html"), "<html>multi</html>");
        write(source.resolve("style.css"), "body { color: black; }");
        write(source.resolve("script.js"), "console.log('ready');");
        AppDeploymentFileManager manager = manager(outputRoot, deploymentRoot);

        try (var staged = manager.stage(CodeGenTypeEnum.MULTI_FILE, APP_ID);
             var published = staged.publishNew(DEPLOY_KEY)) {
            published.commit();
        }

        Path target = deploymentRoot.resolve(DEPLOY_KEY);
        assertAll(
                () -> assertTrue(Files.isRegularFile(target.resolve("index.html"))),
                () -> assertTrue(Files.isRegularFile(target.resolve("style.css"))),
                () -> assertTrue(Files.isRegularFile(target.resolve("script.js"))),
                () -> assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty())
        );
    }

    @Test
    void redeploymentIsExactAndCanRollBackOrCommit() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        AppDeploymentFileManager manager = manager(outputRoot, deploymentRoot);
        Path source = sourceDirectory(outputRoot, CodeGenTypeEnum.HTML, APP_ID);
        write(source.resolve("index.html"), "old");
        write(source.resolve("obsolete.txt"), "remove me");
        publishAndCommit(manager, CodeGenTypeEnum.HTML, APP_ID, false);

        replaceSource(outputRoot, CodeGenTypeEnum.HTML, APP_ID, "new");
        try (var staged = manager.stage(CodeGenTypeEnum.HTML, APP_ID);
             var published = staged.publishReplacement(DEPLOY_KEY)) {
            Path target = deploymentRoot.resolve(DEPLOY_KEY);
            assertEquals("new", read(target.resolve("index.html")));
            assertFalse(Files.exists(target.resolve("obsolete.txt")));
            published.rollback();
        }

        Path target = deploymentRoot.resolve(DEPLOY_KEY);
        assertAll(
                () -> assertEquals("old", read(target.resolve("index.html"))),
                () -> assertEquals("remove me", read(target.resolve("obsolete.txt")))
        );

        try (var staged = manager.stage(CodeGenTypeEnum.HTML, APP_ID);
             var published = staged.publishReplacement(DEPLOY_KEY)) {
            published.commit();
        }

        assertAll(
                () -> assertEquals("new", read(target.resolve("index.html"))),
                () -> assertFalse(Files.exists(target.resolve("obsolete.txt"))),
                () -> assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty())
        );
    }

    @Test
    void firstPublicationRollbackRemovesThePublicTarget() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        write(sourceDirectory(outputRoot, CodeGenTypeEnum.HTML, APP_ID).resolve("index.html"), "new");
        AppDeploymentFileManager manager = manager(outputRoot, deploymentRoot);

        try (var staged = manager.stage(CodeGenTypeEnum.HTML, APP_ID);
             var published = staged.publishNew(DEPLOY_KEY)) {
            assertTrue(Files.isDirectory(deploymentRoot.resolve(DEPLOY_KEY)));
            published.rollback();
        }

        assertAll(
                () -> assertFalse(Files.exists(deploymentRoot.resolve(DEPLOY_KEY))),
                () -> assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty())
        );
    }

    @Test
    void preserveKeepsTheNewTargetAndRollbackBackup() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        AppDeploymentFileManager manager = manager(outputRoot, deploymentRoot);
        Path source = sourceDirectory(outputRoot, CodeGenTypeEnum.HTML, APP_ID);
        write(source.resolve("index.html"), "old");
        publishAndCommit(manager, CodeGenTypeEnum.HTML, APP_ID, false);
        replaceSource(outputRoot, CodeGenTypeEnum.HTML, APP_ID, "new");

        try (var staged = manager.stage(CodeGenTypeEnum.HTML, APP_ID);
             var published = staged.publishReplacement(DEPLOY_KEY)) {
            published.preserve();
        }

        assertAll(
                () -> assertEquals("new", read(deploymentRoot.resolve(DEPLOY_KEY).resolve("index.html"))),
                () -> assertEquals(1, findArtifactsByToken(deploymentRoot, ".backup-").size()),
                () -> assertTrue(findArtifactsByToken(deploymentRoot, ".staging-").isEmpty())
        );
    }

    @Test
    void newPublicationNeverOverwritesAnOrphanTarget() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        write(sourceDirectory(outputRoot, CodeGenTypeEnum.HTML, APP_ID).resolve("index.html"), "new");
        Path orphan = deploymentRoot.resolve(DEPLOY_KEY);
        write(orphan.resolve("index.html"), "orphan");
        AppDeploymentFileManager manager = manager(outputRoot, deploymentRoot);

        assertFalse(manager.isTargetAvailableForNewKey(DEPLOY_KEY));
        try (var staged = manager.stage(CodeGenTypeEnum.HTML, APP_ID)) {
            assertThrows(BusinessException.class, () -> staged.publishNew(DEPLOY_KEY));
        }

        assertAll(
                () -> assertEquals("orphan", read(orphan.resolve("index.html"))),
                () -> assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty())
        );
    }

    @Test
    void newPublicationRejectsATargetCreatedAtTheFinalMove() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        write(sourceDirectory(outputRoot, CodeGenTypeEnum.HTML, APP_ID).resolve("index.html"), "new");
        AppDeploymentFileManager manager = new AppDeploymentFileManager(
                outputRoot,
                deploymentRoot,
                new TargetRacingOperations()
        );

        assertTrue(manager.isTargetAvailableForNewKey(DEPLOY_KEY));
        try (var staged = manager.stage(CodeGenTypeEnum.HTML, APP_ID)) {
            assertThrows(BusinessException.class, () -> staged.publishNew(DEPLOY_KEY));
        }

        assertAll(
                () -> assertEquals(
                        "racing orphan",
                        read(deploymentRoot.resolve(DEPLOY_KEY).resolve("index.html"))
                ),
                () -> assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty())
        );
    }

    @Test
    void rejectsInvalidInputsAndIncompleteSources() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        AppDeploymentFileManager manager = manager(outputRoot, deploymentRoot);

        assertAll(
                () -> assertThrows(BusinessException.class, () -> manager.stage(null, APP_ID)),
                () -> assertThrows(BusinessException.class,
                        () -> manager.stage(CodeGenTypeEnum.HTML, null)),
                () -> assertThrows(BusinessException.class,
                        () -> manager.stage(CodeGenTypeEnum.HTML, 0L)),
                () -> assertThrows(BusinessException.class,
                        () -> manager.stage(CodeGenTypeEnum.HTML, APP_ID)),
                () -> assertThrows(BusinessException.class,
                        () -> manager.isTargetAvailableForNewKey("../bad")),
                () -> assertThrows(BusinessException.class,
                        () -> manager.isTargetAvailableForNewKey("short"))
        );

        Path multiFileSource = sourceDirectory(outputRoot, CodeGenTypeEnum.MULTI_FILE, APP_ID);
        write(multiFileSource.resolve("index.html"), "html");
        write(multiFileSource.resolve("style.css"), "css");
        assertThrows(
                BusinessException.class,
                () -> manager.stage(CodeGenTypeEnum.MULTI_FILE, APP_ID)
        );

        assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty());
    }

    @Test
    void rejectsEverySymbolicLinkWithoutCopyingThroughIt() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        Path source = sourceDirectory(outputRoot, CodeGenTypeEnum.HTML, APP_ID);
        write(source.resolve("index.html"), "html");
        Path external = tempDirectory.resolve("outside-secret.txt");
        write(external, "secret");
        try {
            Files.createSymbolicLink(source.resolve("linked-secret.txt"), external);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable in this environment");
        }
        AppDeploymentFileManager manager = manager(outputRoot, deploymentRoot);

        assertThrows(BusinessException.class, () -> manager.stage(CodeGenTypeEnum.HTML, APP_ID));

        assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty());
    }

    @Test
    void copyFailureCleansItsPartialStagingDirectory() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        write(sourceDirectory(outputRoot, CodeGenTypeEnum.HTML, APP_ID).resolve("index.html"), "html");
        AppDeploymentFileManager manager = new AppDeploymentFileManager(
                outputRoot,
                deploymentRoot,
                new CopyFailingOperations()
        );

        assertThrows(BusinessException.class, () -> manager.stage(CodeGenTypeEnum.HTML, APP_ID));

        assertAll(
                () -> assertFalse(Files.exists(deploymentRoot.resolve(DEPLOY_KEY))),
                () -> assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty())
        );
    }

    @Test
    void replacementMoveFailureRestoresThePriorDirectory() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        write(sourceDirectory(outputRoot, CodeGenTypeEnum.HTML, APP_ID).resolve("index.html"), "new");
        Path target = deploymentRoot.resolve(DEPLOY_KEY);
        write(target.resolve("index.html"), "old");
        AppDeploymentFileManager manager = new AppDeploymentFileManager(
                outputRoot,
                deploymentRoot,
                new FailOnMoveOperations(2)
        );

        try (var staged = manager.stage(CodeGenTypeEnum.HTML, APP_ID)) {
            assertThrows(BusinessException.class, () -> staged.publishReplacement(DEPLOY_KEY));
        }

        assertAll(
                () -> assertEquals("old", read(target.resolve("index.html"))),
                () -> assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty())
        );
    }

    @Test
    void undeploymentCanRestoreCommitAndTreatMissingTargetAsComplete() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        Path target = deploymentRoot.resolve(DEPLOY_KEY);
        write(target.resolve("index.html"), "public");
        AppDeploymentFileManager manager = manager(outputRoot, deploymentRoot);

        try (var undeployment = manager.prepareUndeployment(DEPLOY_KEY)) {
            assertFalse(Files.exists(target));
            assertEquals(1, findArtifactsByToken(deploymentRoot, ".tombstone-").size());
            undeployment.rollback();
        }
        assertEquals("public", read(target.resolve("index.html")));

        try (var undeployment = manager.prepareUndeployment(DEPLOY_KEY)) {
            undeployment.commit();
        }
        assertFalse(Files.exists(target));
        assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty());

        try (var missing = manager.prepareUndeployment(DEPLOY_KEY)) {
            missing.rollback();
        }
        assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty());
    }

    @Test
    void undeploymentMoveFailureLeavesThePublicDirectoryAvailable() throws IOException {
        Path outputRoot = outputRoot();
        Path deploymentRoot = deploymentRoot();
        Path target = deploymentRoot.resolve(DEPLOY_KEY);
        write(target.resolve("index.html"), "public");
        AppDeploymentFileManager manager = new AppDeploymentFileManager(
                outputRoot,
                deploymentRoot,
                new FailOnMoveOperations(1)
        );

        assertThrows(BusinessException.class, () -> manager.prepareUndeployment(DEPLOY_KEY));

        assertAll(
                () -> assertEquals("public", read(target.resolve("index.html"))),
                () -> assertTrue(findTemporaryArtifacts(deploymentRoot).isEmpty())
        );
    }

    @Test
    void rejectsOverlappingConfiguredRoots() {
        Path outputRoot = tempDirectory.resolve("files");

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> manager(outputRoot, outputRoot)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> manager(outputRoot, outputRoot.resolve("deploy"))
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> manager(outputRoot.resolve("generated"), outputRoot)
                )
        );
    }

    private AppDeploymentFileManager manager(Path outputRoot, Path deploymentRoot) {
        return new AppDeploymentFileManager(
                outputRoot,
                deploymentRoot,
                new NioFileTreeOperations()
        );
    }

    private void publishAndCommit(
            AppDeploymentFileManager manager,
            CodeGenTypeEnum codeGenType,
            long appId,
            boolean replaceExisting
    ) {
        try (var staged = manager.stage(codeGenType, appId);
             var published = replaceExisting
                     ? staged.publishReplacement(DEPLOY_KEY)
                     : staged.publishNew(DEPLOY_KEY)) {
            published.commit();
        }
    }

    private void replaceSource(
            Path outputRoot,
            CodeGenTypeEnum codeGenType,
            long appId,
            String indexContent
    ) throws IOException {
        Path source = sourceDirectory(outputRoot, codeGenType, appId);
        new NioFileTreeOperations().deleteTree(source);
        write(source.resolve("index.html"), indexContent);
    }

    private Path sourceDirectory(Path outputRoot, CodeGenTypeEnum codeGenType, long appId) {
        return outputRoot.resolve(codeGenType.getValue() + "_" + appId);
    }

    private Path outputRoot() {
        return tempDirectory.resolve("code-output");
    }

    private Path deploymentRoot() {
        return tempDirectory.resolve("code-deploy");
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static List<Path> findTemporaryArtifacts(Path root) throws IOException {
        if (Files.notExists(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            return entries
                    .filter(path -> path.getFileName().toString().startsWith("."))
                    .toList();
        }
    }

    private static List<Path> findArtifactsByToken(Path root, String token) throws IOException {
        if (Files.notExists(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            return entries
                    .filter(path -> path.getFileName().toString().contains(token))
                    .toList();
        }
    }

    private static final class CopyFailingOperations extends NioFileTreeOperations {

        @Override
        void copyRegularTree(Path source, Path destination) throws IOException {
            write(destination.resolve("partial.txt"), "partial");
            throw new IOException("simulated copy failure");
        }
    }

    private static final class FailOnMoveOperations extends NioFileTreeOperations {

        private final int failingMove;
        private int moveCount;

        private FailOnMoveOperations(int failingMove) {
            this.failingMove = failingMove;
        }

        @Override
        void move(Path source, Path target) throws IOException {
            moveCount++;
            if (moveCount == failingMove) {
                throw new IOException("simulated move failure");
            }
            super.move(source, target);
        }
    }

    private static final class TargetRacingOperations extends NioFileTreeOperations {

        @Override
        void moveWithoutReplacing(Path source, Path target) throws IOException {
            write(target.resolve("index.html"), "racing orphan");
            super.moveWithoutReplacing(source, target);
        }
    }
}
