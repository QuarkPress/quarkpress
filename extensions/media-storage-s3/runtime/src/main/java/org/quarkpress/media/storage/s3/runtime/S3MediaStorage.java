package org.quarkpress.media.storage.s3.runtime;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.KeyTakenException;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.StoredKey;
import org.quarkpress.media.WriteMode;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Keeps media files in an S3 bucket: a key such as {@code 2026/11/photo.jpg} is the object of that key, after the
 * configured key prefix and a {@code /}.
 */
@ApplicationScoped
@Identifier("s3")
public class S3MediaStorage implements MediaStorage {

    /** The largest object S3 takes in one upload: 5 GiB. */
    static final long MAX_SINGLE_PUT = 5L * 1024 * 1024 * 1024;

    /** The longest object key S3 takes, in UTF-8 bytes. */
    private static final int MAX_OBJECT_KEY_BYTES = 1024;

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

    /**
     * Stores {@code source} as the object of {@code key} in one upload, with {@code mimeType} as its
     * {@code Content-Type}. {@link WriteMode#CREATE_NEW} sends {@code If-None-Match: *}, so that S3 refuses the write
     * when the key holds an object, even against a concurrent writer; a conflict with a concurrent write is retried
     * once. With {@code quarkpress.media.s3.conditional-writes=false}, for stores that ignore that header, it asks
     * whether the key holds an object first and then writes: that is not safe against a concurrent writer, which can
     * store its file between the two requests and see it replaced. {@link WriteMode#REPLACE} is a plain upload, which
     * readers see whole or not at all.
     *
     * @throws IOException when the file is larger than S3 takes in one upload (5 GiB), or S3 answers with an error,
     *     naming its status and error code
     */
    @Override
    public void put(String key, Path source, String mimeType, WriteMode mode) throws IOException {
        String objectKey = objectKey(key);
        requireSinglePut(key, Files.size(source));
        boolean createNew = mode == WriteMode.CREATE_NEW;
        if (createNew && !settings.conditionalWrites() && holdsObject(objectKey)) {
            throw new KeyTakenException(key);
        }
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(settings.bucket())
                .key(objectKey)
                .contentType(mimeType);
        boolean conditional = createNew && settings.conditionalWrites();
        if (conditional) {
            request.ifNoneMatch("*");
        }
        PutObjectRequest put = request.build();
        for (int attempt = 1; ; attempt++) {
            try {
                client.putObject(put, RequestBody.fromFile(source));
                return;
            } catch (AwsServiceException error) {
                if (conditional && error.statusCode() == 412) {
                    throw new KeyTakenException(key);
                }
                if (error.statusCode() != 409 || attempt == 2) {
                    throw failure("PutObject", objectKey, error);
                }
            } catch (SdkException error) {
                throw failure("PutObject", objectKey, error);
            }
        }
    }

    /**
     * The object key of the storage key {@code key}: the key prefix, a {@code /} and the key.
     *
     * @throws IllegalArgumentException when {@code key} is not a valid storage key, or the object key is longer than
     *     S3 allows
     */
    String objectKey(String key) {
        StoredKey.require(key);
        String objectKey = settings.keyPrefix().isEmpty() ? key : settings.keyPrefix() + "/" + key;
        int bytes = objectKey.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_OBJECT_KEY_BYTES) {
            throw new IllegalArgumentException("a storage key with the key prefix is at most " + MAX_OBJECT_KEY_BYTES
                    + " bytes in UTF-8 for S3, not " + bytes + ": " + key);
        }
        return objectKey;
    }

    /**
     * Refuses a file that S3 does not take in one upload.
     *
     * @throws IOException when {@code size} is above {@link #MAX_SINGLE_PUT}
     */
    static void requireSinglePut(String key, long size) throws IOException {
        if (size > MAX_SINGLE_PUT) {
            throw new IOException("S3 stores at most 5 GiB in one upload; " + key + " is " + size + " bytes");
        }
    }

    /** Whether the object {@code objectKey} exists: any 404 answer means it does not. */
    private boolean holdsObject(String objectKey) throws IOException {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(settings.bucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (NoSuchKeyException missing) {
            return false;
        } catch (AwsServiceException error) {
            if (error.statusCode() == 404) {
                return false;
            }
            throw failure("HeadObject", objectKey, error);
        } catch (SdkException error) {
            throw failure("HeadObject", objectKey, error);
        }
    }

    /** An {@link IOException} for a failed request, naming the operation, the object, S3's status and error code. */
    IOException failure(String operation, String objectKey, SdkException error) {
        StringBuilder message = new StringBuilder("S3 ")
                .append(operation)
                .append(" of ")
                .append(settings.bucket())
                .append('/')
                .append(objectKey)
                .append(" failed: ");
        if (error instanceof AwsServiceException answer) {
            message.append(answer.statusCode());
            AwsErrorDetails details = answer.awsErrorDetails();
            if (details != null && details.errorCode() != null) {
                message.append(' ').append(details.errorCode());
            }
        } else {
            message.append(error.getMessage());
        }
        return new IOException(message.toString(), error);
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
