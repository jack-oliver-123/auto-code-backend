package com.jack.autocodebackend.core.deploy;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DirectoryPublisherTest {

    @Test
    void committedReplacementRemainsSuccessfulWhenBackupCleanupFails() throws Exception {
        NioFileTreeOperations operations = mock(NioFileTreeOperations.class);
        Path root = Path.of("tmp", "directory-publisher-test").toAbsolutePath().normalize();
        Path staging = root.resolve(".app.staging");
        Path target = root.resolve("app");
        given(operations.isDirectoryNoFollow(staging)).willReturn(true);
        given(operations.existsNoFollow(any(Path.class)))
                .willAnswer(invocation -> target.equals(invocation.getArgument(0)));
        DirectoryPublisher publisher = new DirectoryPublisher(operations);

        DirectoryPublisher.PublishedDirectory publication =
                publisher.publishReplacement(staging, target);
        ArgumentCaptor<Path> backupCaptor = ArgumentCaptor.forClass(Path.class);
        verify(operations).move(eq(target), backupCaptor.capture());
        IOException cleanupFailure = new IOException("backup cleanup failed");
        doThrow(cleanupFailure).when(operations).deleteTree(backupCaptor.getValue());

        assertDoesNotThrow(publication::commit);

        verify(operations).deleteTree(backupCaptor.getValue());
        assertThrows(IllegalStateException.class, publication::rollback);
    }
}
