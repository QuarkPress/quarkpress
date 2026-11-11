package org.quarkpress.media.storage.s3.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.media.WriteMode;

class S3KeysTest {

    private final ScriptedS3Client client = new ScriptedS3Client();

    private S3MediaStorage storage(String prefix) {
        return new S3MediaStorage(
                client, null, new S3Settings("media", prefix, Optional.empty(), Optional.empty(), true));
    }

    @Test
    void objectKeyJoinsThePrefix() {
        assertThat(storage("t1").objectKey("2026/11/a.jpg")).isEqualTo("t1/2026/11/a.jpg");
        assertThat(storage("").objectKey("2026/11/a.jpg")).isEqualTo("2026/11/a.jpg");
    }

    @Test
    void invalidKeysAreRefused() throws Exception {
        Path file = Files.createDirectories(Path.of("target", "s3-keys-test")).resolve("a.txt");
        Files.writeString(file, "a");
        S3MediaStorage storage = storage("t1");
        for (String key : new String[] {"", "/a", "a//b", "a/../b"}) {
            assertThatThrownBy(() -> storage.objectKey(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.put(key, file, "text/plain", WriteMode.CREATE_NEW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.put(key, file, "text/plain", WriteMode.REPLACE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(client.requests).isEmpty();
    }

    @Test
    void aKeyLongerThanS3AllowsWithThePrefixIsRefused() {
        S3MediaStorage storage = storage("p");
        String tooLong = "a".repeat(1023);
        assertThatThrownBy(() -> storage.objectKey(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a storage key with the key prefix is at most 1024 bytes in UTF-8 for S3, not 1025: "
                        + tooLong);
        String longest = "a".repeat(1022);
        assertThat(storage.objectKey(longest)).isEqualTo("p/" + longest);
        assertThat(client.requests).isEmpty();
    }

    @Test
    void aMultiByteKeyIsCountedInUtf8Bytes() {
        // 341 three-byte characters are 1023 bytes: with "p/" that is 1025
        String key = "€".repeat(341);
        assertThatThrownBy(() -> storage("p").objectKey(key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not 1025");
    }

    @Test
    void aFileAboveFiveGibIsRefused() throws Exception {
        assertThatThrownBy(() -> S3MediaStorage.requireSinglePut("k", S3MediaStorage.MAX_SINGLE_PUT + 1))
                .isInstanceOf(IOException.class)
                .hasMessage("S3 stores at most 5 GiB in one upload; k is 5368709121 bytes");
        S3MediaStorage.requireSinglePut("k", S3MediaStorage.MAX_SINGLE_PUT);
    }
}
