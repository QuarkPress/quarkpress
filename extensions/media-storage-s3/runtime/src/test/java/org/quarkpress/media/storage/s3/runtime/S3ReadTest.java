package org.quarkpress.media.storage.s3.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Object;

class S3ReadTest {

    private static final String KEY = "2026/11/a.jpg";

    private final ScriptedS3Client client = new ScriptedS3Client();

    private S3MediaStorage storage(String keyPrefix) {
        return new S3MediaStorage(
                client, null, new S3Settings("media", keyPrefix, Optional.empty(), Optional.empty(), true));
    }

    private S3MediaStorage storage() {
        return storage("t1");
    }

    private static ListObjectsV2Response page(List<String> keys, List<String> commonPrefixes, String next) {
        return ListObjectsV2Response.builder()
                .contents(keys.stream()
                        .map(key -> S3Object.builder().key(key).build())
                        .toList())
                .commonPrefixes(commonPrefixes.stream()
                        .map(prefix -> CommonPrefix.builder().prefix(prefix).build())
                        .toList())
                .isTruncated(next != null)
                .nextContinuationToken(next)
                .build();
    }

    @Test
    void aBare404IsMissing() {
        client.headObject = request -> {
            throw ScriptedS3Client.error(404, "NotFound");
        };
        client.getObject = request -> {
            throw ScriptedS3Client.error(404, "NotFound");
        };
        assertThat(storage().exists(KEY)).isFalse();
        assertThat(storage().size(KEY)).isEmpty();
        assertThatThrownBy(() -> storage().open(KEY))
                .isInstanceOfSatisfying(
                        NoSuchFileException.class,
                        missing -> assertThat(missing.getFile()).isEqualTo(KEY));
    }

    @Test
    void theTypedMissingKeyIsMissing() {
        client.headObject = request -> {
            throw ScriptedS3Client.noSuchKey();
        };
        client.getObject = request -> {
            throw ScriptedS3Client.noSuchKey();
        };
        assertThat(storage().exists(KEY)).isFalse();
        assertThat(storage().size(KEY)).isEmpty();
        assertThatThrownBy(() -> storage().open(KEY)).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void openReturnsTheObjectStream() throws IOException {
        client.getObject = request -> new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(3L).build(),
                AbortableInputStream.create(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))));
        try (InputStream in = storage().open(KEY)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abc");
        }
        assertThat(client.requests(GetObjectRequest.class)).singleElement().satisfies(request -> {
            assertThat(request.bucket()).isEqualTo("media");
            assertThat(request.key()).isEqualTo("t1/" + KEY);
        });
    }

    @Test
    void existsAndSizeAskForTheObject() {
        client.headObject =
                request -> HeadObjectResponse.builder().contentLength(3L).build();
        assertThat(storage().exists(KEY)).isTrue();
        assertThat(storage().size(KEY)).hasValue(3);
        assertThat(client.requests)
                .allSatisfy(request -> assertThat(request)
                        .isInstanceOfSatisfying(
                                HeadObjectRequest.class,
                                head -> assertThat(head.key()).isEqualTo("t1/" + KEY)));
    }

    @Test
    void storeErrorsAreNeverAnswers() {
        client.headObject = request -> {
            throw ScriptedS3Client.error(403, "AccessDenied");
        };
        assertThatThrownBy(() -> storage().exists(KEY))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("AccessDenied");
        assertThatThrownBy(() -> storage().size(KEY))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("AccessDenied");

        client.listObjectsV2 = request -> {
            throw ScriptedS3Client.error(503, "SlowDown");
        };
        assertThatThrownBy(() -> storage().list("2026/11"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("SlowDown");

        client.headObject = request -> {
            throw ScriptedS3Client.error(500, "InternalError");
        };
        assertThatThrownBy(() -> storage().delete(KEY))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("InternalError");
        assertThat(client.requests(DeleteObjectRequest.class)).isEmpty();

        client.getObject = request -> {
            throw ScriptedS3Client.error(500, "InternalError");
        };
        assertThatThrownBy(() -> storage().open(KEY))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("InternalError");
    }

    @Test
    void aMissingBucketIsAnErrorWhereTheAnswerNamesIt() {
        client.getObject = request -> {
            throw ScriptedS3Client.noSuchBucket();
        };
        assertThatThrownBy(() -> storage().open(KEY))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("NoSuchBucket");

        client.listObjectsV2 = request -> {
            throw ScriptedS3Client.noSuchBucket();
        };
        assertThatThrownBy(() -> storage().list("2026/11"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("NoSuchBucket");
    }

    @Test
    void aMissingBucketReadsAsAMissingKeyOnHead() {
        // the SDK turns every 404 answer to a HEAD, a missing bucket's included, into NoSuchKeyException
        client.headObject = request -> {
            throw ScriptedS3Client.noSuchKey();
        };
        assertThat(storage().exists(KEY)).isFalse();
        assertThat(storage().size(KEY)).isEmpty();
        assertThat(storage().delete(KEY)).isFalse();
        assertThat(client.requests(DeleteObjectRequest.class)).isEmpty();
    }

    @Test
    void missingTellsTheShapesOfAMissingKey() {
        assertThat(S3MediaStorage.missing(ScriptedS3Client.noSuchKey())).isTrue();
        assertThat(S3MediaStorage.missing(ScriptedS3Client.error(404, "NotFound")))
                .isTrue();
        assertThat(S3MediaStorage.missing(ScriptedS3Client.error(404, "NoSuchBucket")))
                .isFalse();
        assertThat(S3MediaStorage.missing(
                        NoSuchBucketException.builder().statusCode(404).build()))
                .isFalse();
        assertThat(S3MediaStorage.missing(ScriptedS3Client.error(403, "AccessDenied")))
                .isFalse();
    }

    @Test
    void listFollowsPagesAndSkipsFolderMarkers() {
        client.listObjectsV2 = request -> request.continuationToken() == null
                ? page(List.of("t1/2026/11/a.jpg", "t1/2026/11/"), List.of("t1/2026/11/sub/"), "c1")
                : page(List.of("t1/2026/11/b.jpg"), List.of("t1/2026/11/sub/"), null);

        assertThat(storage().list("2026/11")).containsExactlyInAnyOrder("a.jpg", "sub", "b.jpg");

        List<ListObjectsV2Request> requests = client.requests(ListObjectsV2Request.class);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).bucket()).isEqualTo("media");
        assertThat(requests.get(0).prefix()).isEqualTo("t1/2026/11/");
        assertThat(requests.get(0).delimiter()).isEqualTo("/");
        assertThat(requests.get(0).continuationToken()).isNull();
        assertThat(requests.get(1).continuationToken()).isEqualTo("c1");
        assertThat(requests.get(1).prefix()).isEqualTo("t1/2026/11/");
    }

    @Test
    void listOfTheRootUsesThePrefixAlone() {
        client.listObjectsV2 = request -> page(List.of(), List.of(), null);
        storage().list("");
        assertThat(client.requests(ListObjectsV2Request.class)).singleElement().satisfies(request -> {
            assertThat(request.prefix()).isEqualTo("t1/");
            assertThat(request.delimiter()).isEqualTo("/");
        });

        client.requests.clear();
        client.listObjectsV2 = request -> page(List.of("x.txt"), List.of("2026/"), null);
        assertThat(storage("").list("")).containsExactlyInAnyOrder("x.txt", "2026");
        assertThat(client.requests(ListObjectsV2Request.class))
                .singleElement()
                .satisfies(request -> assertThat(request.prefix()).isNullOrEmpty());
    }

    @Test
    void listRefusesAnInvalidFolderBeforeAnyRequest() {
        assertThatThrownBy(() -> storage().list("/a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage().list("a//b")).isInstanceOf(IllegalArgumentException.class);
        assertThat(client.requests).isEmpty();
    }

    @Test
    void deleteHeadsFirst() {
        client.headObject = request -> {
            throw ScriptedS3Client.noSuchKey();
        };
        client.deleteObject = request -> DeleteObjectResponse.builder().build();
        assertThat(storage().delete(KEY)).isFalse();
        assertThat(client.requests(DeleteObjectRequest.class)).isEmpty();

        client.headObject =
                request -> HeadObjectResponse.builder().contentLength(3L).build();
        assertThat(storage().delete(KEY)).isTrue();
        assertThat(client.requests(DeleteObjectRequest.class)).singleElement().satisfies(request -> {
            assertThat(request.bucket()).isEqualTo("media");
            assertThat(request.key()).isEqualTo("t1/" + KEY);
        });
    }

    @Test
    void aFailedDeleteIsAnError() {
        client.headObject =
                request -> HeadObjectResponse.builder().contentLength(3L).build();
        client.deleteObject = request -> {
            throw ScriptedS3Client.error(403, "AccessDenied");
        };
        assertThatThrownBy(() -> storage().delete(KEY))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("AccessDenied");
    }
}
