package org.quarkpress.media.storage.s3.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.KeyTakenException;
import org.quarkpress.media.WriteMode;
import org.quarkpress.media.storage.s3.runtime.S3MediaStorage;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Writes to a bucket in moto: a new file, a taken key and a replacement. */
class S3PutDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = S3App.active(S3PutDbTest.class);

    @Inject
    @Identifier("s3")
    S3MediaStorage storage;

    @Inject
    S3Client client;

    private static Path file(String name, String content) throws IOException {
        Path directory = Files.createDirectories(S3App.directory("S3PutDbTest"));
        return Files.writeString(directory.resolve(name), content);
    }

    private ResponseBytes<GetObjectResponse> object(String key) {
        return client.getObjectAsBytes(
                request -> request.bucket(S3App.BUCKET).key(storage.settings().keyPrefix() + "/" + key));
    }

    @Test
    void createNewThenTakenThenReplace() throws IOException {
        String key = "2026/11/note.txt";
        storage.put(key, file("one.txt", "one"), "text/plain", WriteMode.CREATE_NEW);
        assertThat(object(key).asUtf8String()).isEqualTo("one");
        assertThat(object(key).response().contentType()).isEqualTo("text/plain");

        assertThatThrownBy(() -> storage.put(key, file("two.txt", "two"), "text/plain", WriteMode.CREATE_NEW))
                .isInstanceOfSatisfying(
                        KeyTakenException.class,
                        taken -> assertThat(taken.key()).isEqualTo(key));
        assertThat(object(key).asUtf8String()).isEqualTo("one");

        storage.put(key, file("three.txt", "three"), "text/plain", WriteMode.REPLACE);
        assertThat(object(key).asUtf8String()).isEqualTo("three");
    }

    @Test
    void anEmptyFileIsStored() throws IOException {
        String key = "2026/11/empty.bin";
        storage.put(key, file("empty.bin", ""), "application/octet-stream", WriteMode.CREATE_NEW);
        assertThat(object(key).response().contentLength()).isZero();
        assertThat(object(key).asByteArray()).isEmpty();
    }
}
