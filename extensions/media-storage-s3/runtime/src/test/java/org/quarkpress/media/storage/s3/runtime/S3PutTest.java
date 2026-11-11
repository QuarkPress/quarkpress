package org.quarkpress.media.storage.s3.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quarkpress.media.KeyTakenException;
import org.quarkpress.media.WriteMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class S3PutTest {

    private static final String KEY = "2026/11/a.jpg";

    private final ScriptedS3Client client = new ScriptedS3Client();
    private Path file;

    @BeforeEach
    void file() throws IOException {
        file = Files.createDirectories(Path.of("target", "s3-put-test")).resolve("a.jpg");
        Files.writeString(file, "jpeg");
    }

    private S3MediaStorage storage(boolean conditionalWrites) {
        return new S3MediaStorage(
                client, null, new S3Settings("media", "t1", Optional.empty(), Optional.empty(), conditionalWrites));
    }

    private static PutObjectResponse stored(PutObjectRequest request) {
        return PutObjectResponse.builder().build();
    }

    @Test
    void createNewSendsIfNoneMatchAndTheType() throws IOException {
        client.putObject = S3PutTest::stored;
        storage(true).put(KEY, file, "image/jpeg", WriteMode.CREATE_NEW);
        assertThat(client.requests(PutObjectRequest.class)).singleElement().satisfies(request -> {
            assertThat(request.bucket()).isEqualTo("media");
            assertThat(request.key()).isEqualTo("t1/" + KEY);
            assertThat(request.ifNoneMatch()).isEqualTo("*");
            assertThat(request.contentType()).isEqualTo("image/jpeg");
        });
    }

    @Test
    void replaceIsAPlainPut() throws IOException {
        client.putObject = S3PutTest::stored;
        storage(true).put(KEY, file, "image/jpeg", WriteMode.REPLACE);
        assertThat(client.requests).singleElement().isInstanceOfSatisfying(PutObjectRequest.class, request -> {
            assertThat(request.key()).isEqualTo("t1/" + KEY);
            assertThat(request.ifNoneMatch()).isNull();
            assertThat(request.contentType()).isEqualTo("image/jpeg");
        });
    }

    @Test
    void a412IsKeyTaken() {
        client.putObject = request -> {
            throw ScriptedS3Client.error(412, "PreconditionFailed");
        };
        assertThatThrownBy(() -> storage(true).put(KEY, file, "image/jpeg", WriteMode.CREATE_NEW))
                .isInstanceOfSatisfying(
                        KeyTakenException.class,
                        taken -> assertThat(taken.key()).isEqualTo(KEY));
        assertThat(client.requests).hasSize(1);
    }

    @Test
    void a409IsRetriedOnce() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        client.putObject = request -> {
            if (calls.incrementAndGet() == 1) {
                throw ScriptedS3Client.error(409, "ConditionalRequestConflict");
            }
            return stored(request);
        };
        storage(true).put(KEY, file, "image/jpeg", WriteMode.CREATE_NEW);
        assertThat(client.requests(PutObjectRequest.class))
                .hasSize(2)
                .allSatisfy(request -> assertThat(request.ifNoneMatch()).isEqualTo("*"));
    }

    @Test
    void twoConflictsAreAnIOException() {
        client.putObject = request -> {
            throw ScriptedS3Client.error(409, "ConditionalRequestConflict");
        };
        assertThatThrownBy(() -> storage(true).put(KEY, file, "image/jpeg", WriteMode.CREATE_NEW))
                .isExactlyInstanceOf(IOException.class)
                .hasMessageContaining("ConditionalRequestConflict")
                .hasMessageContaining("409");
        assertThat(client.requests(PutObjectRequest.class)).hasSize(2);
    }

    @Test
    void withoutConditionalWritesHeadThenPut() throws IOException {
        client.headObject =
                request -> HeadObjectResponse.builder().contentLength(4L).build();
        assertThatThrownBy(() -> storage(false).put(KEY, file, "image/jpeg", WriteMode.CREATE_NEW))
                .isInstanceOfSatisfying(
                        KeyTakenException.class,
                        taken -> assertThat(taken.key()).isEqualTo(KEY));
        assertThat(client.requests).singleElement().isInstanceOfSatisfying(HeadObjectRequest.class, request -> {
            assertThat(request.bucket()).isEqualTo("media");
            assertThat(request.key()).isEqualTo("t1/" + KEY);
        });

        client.requests.clear();
        client.headObject = request -> {
            throw ScriptedS3Client.noSuchKey();
        };
        client.putObject = S3PutTest::stored;
        storage(false).put(KEY, file, "image/jpeg", WriteMode.CREATE_NEW);
        assertThat(client.requests).hasSize(2);
        assertThat(client.requests.get(0)).isInstanceOf(HeadObjectRequest.class);
        assertThat(client.requests.get(1)).isInstanceOfSatisfying(PutObjectRequest.class, request -> {
            assertThat(request.key()).isEqualTo("t1/" + KEY);
            assertThat(request.ifNoneMatch()).isNull();
        });
    }

    @Test
    void withoutConditionalWritesAHeadErrorIsAnIOException() {
        client.headObject = request -> {
            throw ScriptedS3Client.error(403, "AccessDenied");
        };
        assertThatThrownBy(() -> storage(false).put(KEY, file, "image/jpeg", WriteMode.CREATE_NEW))
                .isExactlyInstanceOf(IOException.class)
                .hasMessageContaining("AccessDenied");
        assertThat(client.requests(PutObjectRequest.class)).isEmpty();
    }

    @Test
    void otherPutErrorsAreIOExceptions() {
        client.putObject = request -> {
            throw ScriptedS3Client.error(403, "AccessDenied");
        };
        for (WriteMode mode : WriteMode.values()) {
            assertThatThrownBy(() -> storage(true).put(KEY, file, "image/jpeg", mode))
                    .isExactlyInstanceOf(IOException.class)
                    .hasMessageContaining("AccessDenied")
                    .hasMessageContaining("403");
        }
        assertThat(client.requests).hasSize(2);
    }
}
