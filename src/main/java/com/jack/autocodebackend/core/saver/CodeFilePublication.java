package com.jack.autocodebackend.core.saver;

import com.jack.autocodebackend.core.deploy.DirectoryPublisher;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * A generated-code directory replacement that can still be committed or rolled back.
 */
public final class CodeFilePublication implements AutoCloseable {

    private final File directory;
    private final DirectoryPublisher.PublishedDirectory publication;

    CodeFilePublication(
            File directory,
            DirectoryPublisher.PublishedDirectory publication
    ) {
        this.directory = Objects.requireNonNull(directory);
        this.publication = Objects.requireNonNull(publication);
    }

    public File directory() {
        return directory;
    }

    public void commit() {
        publication.commit();
    }

    public void rollback() throws IOException {
        publication.rollback();
    }

    @Override
    public void close() throws IOException {
        publication.close();
    }
}
