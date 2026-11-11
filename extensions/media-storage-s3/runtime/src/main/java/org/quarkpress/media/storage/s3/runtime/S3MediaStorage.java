package org.quarkpress.media.storage.s3.runtime;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
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
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

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

    /** S3's error code for a bucket that does not exist. */
    private static final String NO_SUCH_BUCKET = "NoSuchBucket";

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

    /** Whether the object {@code objectKey} exists. */
    private boolean holdsObject(String objectKey) throws IOException {
        return head(objectKey) != null;
    }

    /**
     * The metadata of the object {@code objectKey}, or null when the key holds no object.
     *
     * @throws IOException when S3 answers with an error other than a missing key
     */
    private HeadObjectResponse head(String objectKey) throws IOException {
        try {
            return client.headObject(HeadObjectRequest.builder()
                    .bucket(settings.bucket())
                    .key(objectKey)
                    .build());
        } catch (S3Exception error) {
            if (missing(error)) {
                return null;
            }
            throw failure("HeadObject", objectKey, error);
        } catch (SdkException error) {
            throw failure("HeadObject", objectKey, error);
        }
    }

    /**
     * Whether S3's answer means that the key holds no object: a {@link NoSuchKeyException}, or any 404 answer except
     * a missing bucket, which is a configuration error rather than a missing file. Some S3-compatible stores carry a
     * 404 without the typed exception. Only an answer with a body, such as a {@code GET}'s, names a missing bucket: the
     * SDK turns every 404 answer to a {@code HEAD} into a {@link NoSuchKeyException}, so {@link #head} reads a missing
     * bucket as a missing key.
     */
    static boolean missing(S3Exception error) {
        if (error instanceof NoSuchKeyException) {
            return true;
        }
        if (error instanceof NoSuchBucketException || error.statusCode() != 404) {
            return false;
        }
        AwsErrorDetails details = error.awsErrorDetails();
        return details == null || !NO_SUCH_BUCKET.equals(details.errorCode());
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

    /**
     * Opens the object of {@code key} for reading; the caller closes the stream, which reads the answer's body.
     *
     * @throws NoSuchFileException when the key holds no object
     * @throws IOException when S3 answers with another error, naming its status and error code
     */
    @Override
    public InputStream open(String key) throws IOException {
        String objectKey = objectKey(key);
        try {
            return client.getObject(GetObjectRequest.builder()
                    .bucket(settings.bucket())
                    .key(objectKey)
                    .build());
        } catch (S3Exception error) {
            if (missing(error)) {
                throw new NoSuchFileException(key);
            }
            throw failure("GetObject", objectKey, error);
        } catch (SdkException error) {
            throw failure("GetObject", objectKey, error);
        }
    }

    /**
     * Whether {@code key} holds an object.
     *
     * @throws UncheckedIOException when S3 answers with an error other than a missing key
     */
    @Override
    public boolean exists(String key) {
        return headUnchecked(objectKey(key)) != null;
    }

    /**
     * The names of the direct entries of the folder {@code folderKey} ({@code ""} for the root, below the key
     * prefix): its objects and the next segments of the deeper ones, read page by page. A zero-byte folder marker,
     * such as the S3 console makes for {@code 2026/11/}, is not an entry, and nothing outside the key prefix is listed.
     *
     * @throws UncheckedIOException when S3 answers with an error
     */
    @Override
    public List<String> list(String folderKey) {
        String prefix;
        if (folderKey.isEmpty()) {
            prefix = settings.keyPrefix().isEmpty() ? null : settings.keyPrefix() + "/";
        } else {
            prefix = objectKey(folderKey) + "/";
        }
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(settings.bucket())
                .prefix(prefix)
                .delimiter("/")
                .build();
        String start = prefix == null ? "" : prefix;
        Set<String> names = new LinkedHashSet<>();
        try {
            // the pages are read once: the iterable's own contents() and commonPrefixes() each send every request again
            for (ListObjectsV2Response page : client.listObjectsV2Paginator(request)) {
                for (S3Object object : page.contents()) {
                    addName(names, start, object.key());
                }
                for (CommonPrefix folder : page.commonPrefixes()) {
                    String folderPrefix = folder.prefix();
                    addName(
                            names,
                            start,
                            folderPrefix.endsWith("/")
                                    ? folderPrefix.substring(0, folderPrefix.length() - 1)
                                    : folderPrefix);
                }
            }
        } catch (SdkException error) {
            throw new UncheckedIOException(failure("ListObjectsV2", start, error));
        }
        return List.copyOf(names);
    }

    /** Adds the name of {@code objectKey} below {@code start}, when it is one: never empty, never outside it. */
    private static void addName(Set<String> names, String start, String objectKey) {
        if (objectKey != null && objectKey.startsWith(start) && objectKey.length() > start.length()) {
            names.add(objectKey.substring(start.length()));
        }
    }

    /**
     * Removes the object of {@code key}: false when there was none. S3 answers a delete alike whether or not the key
     * held an object, so this asks first; two concurrent deletes of one key can therefore both answer true.
     *
     * @throws UncheckedIOException when S3 answers with an error other than a missing key
     */
    @Override
    public boolean delete(String key) {
        String objectKey = objectKey(key);
        if (headUnchecked(objectKey) == null) {
            return false;
        }
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(settings.bucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (SdkException error) {
            throw new UncheckedIOException(failure("DeleteObject", objectKey, error));
        }
    }

    /**
     * The size of the object of {@code key} in bytes, or empty when there is none.
     *
     * @throws UncheckedIOException when S3 answers with an error other than a missing key
     */
    @Override
    public OptionalLong size(String key) {
        String objectKey = objectKey(key);
        HeadObjectResponse head = headUnchecked(objectKey);
        if (head == null) {
            return OptionalLong.empty();
        }
        Long length = head.contentLength();
        if (length == null) {
            throw new UncheckedIOException(new IOException(
                    "S3 HeadObject of " + settings.bucket() + '/' + objectKey + " answered without a Content-Length"));
        }
        return OptionalLong.of(length);
    }

    /** {@link #head} for the methods that cannot throw {@link IOException}. */
    private HeadObjectResponse headUnchecked(String objectKey) {
        try {
            return head(objectKey);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /**
     * The URL of the file of {@code key}, for the whole network ({@code site} is not read: the keys of a site's files
     * already start with its {@code sites/<id>/} folder). With {@code quarkpress.media.s3.public-base-url}, the object
     * key joined to that URL as it is, for a CDN or a public bucket; otherwise with
     * {@code quarkpress.media.s3.presigned-url-ttl}, a presigned URL that is valid that long, so a URL saved in a post
     * stops working when it expires; otherwise the object's URL at the bucket, which serves the file only when the
     * bucket is public. The presigned and bucket URLs encode the key and follow the endpoint and path-style settings
     * of the S3 client. No request is sent.
     *
     * @throws IllegalArgumentException when {@code key} is not a valid storage key, or the object key is longer than
     *     S3 allows
     */
    @Override
    public String publicUrl(SiteInfo site, String key) {
        String objectKey = objectKey(key);
        if (settings.publicBaseUrl().isPresent()) {
            return publicBaseUrl(objectKey);
        }
        if (settings.presignedUrlTtl().isPresent()) {
            return presigner
                    .presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(settings.presignedUrlTtl().get())
                            .getObjectRequest(
                                    request -> request.bucket(settings.bucket()).key(objectKey))
                            .build())
                    .url()
                    .toString();
        }
        return bucketUrl(objectKey);
    }

    /**
     * The permanent URL of the file of {@code key}, recorded as its attachment's guid: the public base URL form when
     * that is set, otherwise the object's URL at the bucket, never presigned (a presigned URL expires, and is longer
     * than a guid may be). No request is sent.
     *
     * @throws IllegalArgumentException when {@code key} is not a valid storage key, or the object key is longer than
     *     S3 allows
     */
    @Override
    public String guidUrl(SiteInfo site, String key) {
        String objectKey = objectKey(key);
        return settings.publicBaseUrl().isPresent() ? publicBaseUrl(objectKey) : bucketUrl(objectKey);
    }

    /** The object's URL at the bucket, as the S3 client addresses it (endpoint and path style). */
    private String bucketUrl(String objectKey) {
        return client.utilities()
                .getUrl(GetUrlRequest.builder()
                        .bucket(settings.bucket())
                        .key(objectKey)
                        .build())
                .toString();
    }

    /** The object's URL below the public base URL, which {@link S3Settings} keeps without trailing slashes. */
    private String publicBaseUrl(String objectKey) {
        return settings.publicBaseUrl().orElseThrow() + "/" + objectKey;
    }
}
