package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.transaction.Status;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.WriteMode;

/** A stored file goes again only when the transaction naming it rolled back, never on an unknown outcome. */
class StoredFilesTest {

    @Test
    void onlyARollbackRemovesTheFile() {
        assertThat(deletesAfter(Status.STATUS_ROLLEDBACK)).containsExactly("2026/11/x.jpg");
        assertThat(deletesAfter(Status.STATUS_COMMITTED)).isEmpty();
        // the database may have committed the row naming the file: it is kept
        assertThat(deletesAfter(Status.STATUS_UNKNOWN)).isEmpty();
        assertThat(deletesAfter(Status.STATUS_NO_TRANSACTION)).isEmpty();
    }

    private static List<String> deletesAfter(int status) {
        List<String> deleted = new CopyOnWriteArrayList<>();
        MediaStorage storage = new MediaStorage() {
            @Override
            public void put(String key, Path source, String mimeType, WriteMode mode) {}

            @Override
            public InputStream open(String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean exists(String key) {
                return true;
            }

            @Override
            public List<String> list(String folderKey) {
                return List.of();
            }

            @Override
            public boolean delete(String key) {
                deleted.add(key);
                return true;
            }

            @Override
            public OptionalLong size(String key) {
                return OptionalLong.empty();
            }

            @Override
            public String publicUrl(SiteInfo site, String key) {
                return key;
            }
        };
        ActiveStorage active = new ActiveStorage() {
            @Override
            public MediaStorage storage() {
                return storage;
            }
        };
        new StoredFiles.RemoveOnRollback(active, "2026/11/x.jpg").afterCompletion(status);
        return deleted;
    }
}
