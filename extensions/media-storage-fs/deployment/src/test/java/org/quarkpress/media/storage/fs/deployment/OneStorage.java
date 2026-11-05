package org.quarkpress.media.storage.fs.deployment;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.WriteMode;

/** An empty storage backend named {@code one}, chosen over the filesystem backend. */
@ApplicationScoped
@Identifier("one")
public class OneStorage implements MediaStorage {

    @Override
    public void put(String key, Path source, String mimeType, WriteMode mode) throws IOException {
        throw new IOException("read-only test storage");
    }

    @Override
    public InputStream open(String key) throws IOException {
        throw new NoSuchFileException(key);
    }

    @Override
    public boolean exists(String key) {
        return false;
    }

    @Override
    public List<String> list(String folderKey) {
        return List.of();
    }

    @Override
    public boolean delete(String key) {
        return false;
    }

    @Override
    public OptionalLong size(String key) {
        return OptionalLong.empty();
    }

    @Override
    public String publicUrl(SiteInfo site, String key) {
        return site.url("http") + "one/" + key;
    }
}
