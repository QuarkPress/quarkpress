package org.quarkpress.media.image.imageio.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.WriteMode;

/** The storage backend the media module needs to start; these tests store nothing. */
@ApplicationScoped
public class TestStorage implements MediaStorage {

    @Override
    public void put(String key, Path source, String mimeType, WriteMode mode) {
        throw new UnsupportedOperationException("these tests store nothing");
    }

    @Override
    public InputStream open(String key) throws NoSuchFileException {
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
        return site.url("http") + "/media/" + key;
    }
}
