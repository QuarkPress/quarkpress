package org.quarkpress.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What every {@link MediaStorage} backend does, whatever it keeps its files in: a backend's test extends this class
 * and answers {@link #storage()}. Each test works under its own folder {@code run-<uuid>/}, so a storage shared by the
 * tests (a bucket, a directory) needs no cleaning; the files it uploads are written under
 * {@code target/storage-contract/}.
 */
public abstract class MediaStorageContract {

    private static final int WRITERS = 8;

    /** The folder of the running test, {@code run-<uuid>}. */
    private String run;

    private Path files;

    /** The storage under test; the same one for every call within a test. */
    protected abstract MediaStorage storage();

    @BeforeEach
    void newRun() throws IOException {
        run = "run-" + UUID.randomUUID();
        files = Files.createDirectories(
                Path.of("target", "storage-contract", run).toAbsolutePath());
    }

    private Path file(String content) throws IOException {
        return Files.writeString(files.resolve(UUID.randomUUID() + ".txt"), content);
    }

    private String read(String key) throws IOException {
        try (InputStream in = storage().open(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void put(String key, String content, WriteMode mode) throws IOException {
        storage().put(key, file(content), "text/plain", mode);
    }

    @Test
    void putThenOpenGivesTheBytes() throws IOException {
        String key = run + "/open.txt";
        put(key, "the bytes", WriteMode.CREATE_NEW);
        assertThat(read(key)).isEqualTo("the bytes");
    }

    @Test
    void sizeIsTheByteCount() throws IOException {
        put(run + "/five.txt", "hello", WriteMode.CREATE_NEW);
        put(run + "/empty.txt", "", WriteMode.CREATE_NEW);
        assertThat(storage().size(run + "/five.txt")).hasValue(5);
        assertThat(storage().size(run + "/empty.txt")).hasValue(0);
    }

    @Test
    void createNewOnATakenKeyKeepsTheFile() throws IOException {
        String key = run + "/taken.txt";
        put(key, "first", WriteMode.CREATE_NEW);
        assertThatThrownBy(() -> put(key, "second", WriteMode.CREATE_NEW))
                .isInstanceOfSatisfying(
                        KeyTakenException.class,
                        taken -> assertThat(taken.key()).isEqualTo(key));
        assertThat(read(key)).isEqualTo("first");
    }

    @Test
    void replaceReplaces() throws IOException {
        String key = run + "/replaced.txt";
        put(key, "old", WriteMode.CREATE_NEW);
        put(key, "new", WriteMode.REPLACE);
        assertThat(read(key)).isEqualTo("new");
    }

    @Test
    void missingKeys() {
        String key = run + "/missing.txt";
        assertThat(storage().exists(key)).isFalse();
        assertThat(storage().size(key)).isEmpty();
        assertThatThrownBy(() -> storage().open(key)).isInstanceOf(NoSuchFileException.class);
        assertThat(storage().delete(key)).isFalse();
    }

    @Test
    void listOneLevel() throws IOException {
        put(run + "/a/b/c.txt", "c", WriteMode.CREATE_NEW);
        put(run + "/a/d.txt", "d", WriteMode.CREATE_NEW);
        assertThat(storage().list(run + "/a")).containsExactlyInAnyOrder("b", "d.txt");
        assertThat(storage().list(run + "/none")).isEmpty();
        assertThat(storage().list("")).contains(run);
    }

    @Test
    void deleteRemoves() throws IOException {
        String key = run + "/deleted.txt";
        put(key, "gone", WriteMode.CREATE_NEW);
        assertThat(storage().delete(key)).isTrue();
        assertThat(storage().exists(key)).isFalse();
        assertThat(storage().delete(key)).isFalse();
    }

    @Test
    void keysWithSpacesAndNonAscii() throws IOException {
        String key = run + "/a b/café.txt";
        put(key, "café", WriteMode.CREATE_NEW);
        assertThat(read(key)).isEqualTo("café");
        assertThat(storage().exists(key)).isTrue();
        assertThat(storage().list(run + "/a b")).containsExactly("café.txt");
    }

    @Test
    void invalidKeysAreRefusedEverywhere() throws IOException {
        Path source = file("never stored");
        for (String key : List.of("", "/a", "a//b", "a/../b", "a\\b", "a\u0000b")) {
            assertThatThrownBy(() -> storage().put(key, source, "text/plain", WriteMode.CREATE_NEW))
                    .as("put %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage().open(key))
                    .as("open %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage().exists(key))
                    .as("exists %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage().size(key))
                    .as("size %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage().delete(key))
                    .as("delete %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            if (!key.isEmpty()) {
                assertThatThrownBy(() -> storage().list(key))
                        .as("list %s", key)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void concurrentCreatesHaveOneWinner() throws Exception {
        String key = run + "/raced.txt";
        List<Path> sources = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            sources.add(file("writer " + i));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        try {
            List<Future<Integer>> writers = new ArrayList<>();
            for (int i = 0; i < WRITERS; i++) {
                int writer = i;
                writers.add(pool.submit(() -> {
                    start.await();
                    try {
                        storage().put(key, sources.get(writer), "text/plain", WriteMode.CREATE_NEW);
                        return writer;
                    } catch (KeyTakenException taken) {
                        return -1;
                    }
                }));
            }
            start.countDown();
            List<Integer> winners = new ArrayList<>();
            int taken = 0;
            for (Future<Integer> writer : writers) {
                int outcome = writer.get(2, TimeUnit.MINUTES);
                if (outcome < 0) {
                    taken++;
                } else {
                    winners.add(outcome);
                }
            }
            assertThat(winners).as("writers that stored the file").hasSize(1);
            assertThat(taken).as("writers told the key is taken").isEqualTo(WRITERS - 1);
            assertThat(read(key)).isEqualTo("writer " + winners.get(0));
        } finally {
            pool.shutdownNow();
        }
    }
}
