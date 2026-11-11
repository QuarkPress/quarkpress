package org.quarkpress.media.storage.s3.runtime;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.WriteMode;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Keeps media files in an S3 bucket: a key such as {@code 2026/11/photo.jpg} is the object of that key, after the
 * configured key prefix and a {@code /}.
 */
@ApplicationScoped
@Identifier("s3")
public class S3MediaStorage implements MediaStorage {

    private static final String NOT_YET = "not implemented yet";

    private final S3Client client;
    private final S3Presigner presigner;
    private final S3Settings settings;

    @Inject
    public S3MediaStorage(S3Client client, S3Presigner presigner, S3Config config) {
        this(client, presigner, S3Settings.of(config));
    }

    /** A storage with the given client, presigner and settings. */
    public S3MediaStorage(S3Client client, S3Presigner presigner, S3Settings settings) {
        this.client = client;
        this.presigner = presigner;
        this.settings = settings;
    }

    /** The settings this storage works with. */
    public S3Settings settings() {
        return settings;
    }

    @Override
    public void put(String key, Path source, String mimeType, WriteMode mode) throws IOException {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public InputStream open(String key) throws IOException {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public boolean exists(String key) {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public List<String> list(String folderKey) {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public boolean delete(String key) {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public OptionalLong size(String key) {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public String publicUrl(SiteInfo site, String key) {
        throw new UnsupportedOperationException(NOT_YET);
    }
}
