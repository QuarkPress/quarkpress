package org.quarkpress.media.storage.s3.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.WriteMode;
import org.quarkpress.media.storage.s3.runtime.S3MediaStorage;
import org.quarkpress.media.storage.s3.runtime.S3Settings;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** Reads, lists and deletes in a bucket in moto, which also holds objects QuarkPress did not write. */
class S3ReadDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = S3App.active(S3ReadDbTest.class);

    @Inject
    @Identifier("s3")
    S3MediaStorage storage;

    @Inject
    S3Client client;

    private static Path file(String name, String content) throws IOException {
        Path directory = Files.createDirectories(S3App.directory("S3ReadDbTest"));
        return Files.writeString(directory.resolve(name), content);
    }

    private void put(String key, String content) throws IOException {
        storage.put(key, file("upload.txt", content), "text/plain", WriteMode.REPLACE);
    }

    private void putRaw(String objectKey) {
        client.putObject(request -> request.bucket(S3App.BUCKET).key(objectKey), RequestBody.empty());
    }

    @Test
    void openReadsTheBytes() throws IOException {
        put("read/open.txt", "the bytes");
        try (InputStream in = storage.open("read/open.txt")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("the bytes");
        }
        assertThatThrownBy(() -> storage.open("read/missing.txt"))
                .isInstanceOfSatisfying(
                        NoSuchFileException.class,
                        missing -> assertThat(missing.getFile()).isEqualTo("read/missing.txt"));
    }

    @Test
    void existsAndSize() throws IOException {
        put("size/three.txt", "abc");
        assertThat(storage.exists("size/three.txt")).isTrue();
        assertThat(storage.size("size/three.txt")).hasValue(3);
        assertThat(storage.exists("size/missing.txt")).isFalse();
        assertThat(storage.size("size/missing.txt")).isEmpty();
    }

    @Test
    void listStaysInsideThePrefix() throws IOException {
        String prefix = storage.settings().keyPrefix();
        put("a/x.txt", "x");
        put("a/b/y.txt", "y");
        putRaw("other/a/z.txt");
        putRaw(prefix + "/a/");

        assertThat(storage.list("a")).containsExactlyInAnyOrder("x.txt", "b");
        assertThat(storage.list("missing")).isEmpty();
    }

    @Test
    void deleteAnswersWhetherItWasThere() throws IOException {
        put("delete/gone.txt", "gone");
        assertThat(storage.delete("delete/gone.txt")).isTrue();
        assertThat(storage.delete("delete/gone.txt")).isFalse();
        assertThat(storage.exists("delete/gone.txt")).isFalse();
    }

    @Test
    void aMissingBucketFailsWhereTheAnswerNamesItAndIsMissingOnHead() throws IOException {
        S3MediaStorage nowhere = new S3MediaStorage(
                client,
                null,
                new S3Settings("quarkpress-no-such-bucket", "", Optional.empty(), Optional.empty(), true));
        Path upload = file("nowhere.txt", "x");
        assertThatThrownBy(() -> nowhere.put("a.txt", upload, "text/plain", WriteMode.CREATE_NEW))
                .isExactlyInstanceOf(IOException.class)
                .hasMessageContaining("NoSuchBucket");
        assertThatThrownBy(() -> nowhere.open("a.txt"))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("NoSuchBucket");
        assertThatThrownBy(() -> nowhere.list(""))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("NoSuchBucket");
        // a HEAD answer has no body: the SDK reads its 404 as a missing key
        assertThat(nowhere.exists("a.txt")).isFalse();
        assertThat(nowhere.size("a.txt")).isEmpty();
        assertThat(nowhere.delete("a.txt")).isFalse();
    }
}
