package org.quarkpress.media.storage.fs.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.MediaStorageContract;

/** The storage contract on the filesystem backend, over a fresh directory below target/. */
class FsMediaStorageContractTest extends MediaStorageContract {

    private final FsMediaStorage storage;

    FsMediaStorageContractTest() {
        try {
            Path directory = Files.createDirectories(Path.of("target", "storage-contract", "fs-" + UUID.randomUUID())
                    .toAbsolutePath());
            storage = new FsMediaStorage(directory, Optional.empty(), "/wp-content/uploads");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    protected MediaStorage storage() {
        return storage;
    }
}
