package org.quarkpress.media.storage.fs.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.KeyTakenException;
import org.quarkpress.media.WriteMode;

/** The filesystem storage backend: writes that never replace by accident, and keys that never leave the directory. */
class FsMediaStorageTest {

    private static final int MIB = 1024 * 1024;

    private Path base;
    private Path dir;
    private FsMediaStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(
                Path.of("target", "fs-storage-test", UUID.randomUUID().toString())
                        .toAbsolutePath());
        dir = Files.createDirectories(base.resolve("uploads"));
        storage = new FsMediaStorage(dir, Optional.empty(), "/wp-content/uploads");
    }

    @Test
    void createNewNeverReplacesAnExistingFile() throws Exception {
        storage.put("2026/11/photo.jpg", file("one"), "image/jpeg", WriteMode.CREATE_NEW);
        assertThatThrownBy(() -> storage.put("2026/11/photo.jpg", file("two"), "image/jpeg", WriteMode.CREATE_NEW))
                .isInstanceOf(KeyTakenException.class);
        assertThat(read("2026/11/photo.jpg")).isEqualTo("one");

        race(storage);
    }

    @Test
    void theFallbackNeverReplacesAnExistingFile() throws Exception {
        FsMediaStorage fallback = new FsMediaStorage(dir, Optional.empty(), "/wp-content/uploads", true);
        fallback.put("2026/11/photo.jpg", file("one"), "image/jpeg", WriteMode.CREATE_NEW);
        assertThatThrownBy(() -> fallback.put("2026/11/photo.jpg", file("two"), "image/jpeg", WriteMode.CREATE_NEW))
                .isInstanceOf(KeyTakenException.class);
        assertThat(read("2026/11/photo.jpg")).isEqualTo("one");

        race(fallback);
    }

    /**
     * Two writers of one new key released together: one wins with all its bytes, the other is told the key is taken.
     */
    private void race(FsMediaStorage target) throws Exception {
        for (int round = 0; round < 20; round++) {
            String key = "2026/11/race-" + round + ".jpg";
            Path a = bytes('a', MIB);
            Path b = bytes('b', MIB);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger taken = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<?>> writes = new ArrayList<>();
                for (Path source : List.of(a, b)) {
                    writes.add(pool.submit(() -> {
                        go.await();
                        try {
                            target.put(key, source, "image/jpeg", WriteMode.CREATE_NEW);
                        } catch (KeyTakenException expected) {
                            taken.incrementAndGet();
                        }
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> write : writes) {
                    write.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(taken.get()).as("round %d", round).isEqualTo(1);
            byte[] stored = Files.readAllBytes(dir.resolve(key));
            assertThat(stored.length).isEqualTo(MIB);
            assertThat(isAll(stored, (byte) 'a') || isAll(stored, (byte) 'b'))
                    .as("one writer's complete bytes")
                    .isTrue();
        }
        assertThat(temporaryFiles()).isEmpty();
    }

    @Test
    void temporaryFilesStayOutOfSight() throws Exception {
        storage.put("2026/11/a.jpg", file("a"), "image/jpeg", WriteMode.CREATE_NEW);
        Path temporary = dir.resolve(".tmp");
        Path stale = Files.writeString(temporary.resolve("x.tmp"), "stale");
        Path fresh = Files.writeString(temporary.resolve("y.tmp"), "fresh");
        Instant now = Instant.parse("2026-11-05T12:00:00Z");
        Files.setLastModifiedTime(stale, FileTime.from(now.minus(Duration.ofMinutes(61))));
        Files.setLastModifiedTime(fresh, FileTime.from(now.minus(Duration.ofMinutes(59))));

        assertThat(storage.list("")).containsExactly("2026");
        assertThatThrownBy(() -> storage.exists(".tmp/x.tmp")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.list(".tmp")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(".tmp/x.tmp")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(".tmp/x.tmp")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put(".tmp/z.tmp", file("z"), "image/jpeg", WriteMode.REPLACE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.servable(".tmp/x.tmp")).isEmpty();

        assertThat(storage.sweep(now)).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
    }

    @Test
    void replaceIsAtomic() throws Exception {
        String key = "2026/11/replace.jpg";
        Path a = bytes('a', MIB);
        Path b = bytes('b', MIB);
        storage.put(key, a, "image/jpeg", WriteMode.CREATE_NEW);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> torn = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        Thread reader = new Thread(() -> {
            while (!done.get()) {
                try (InputStream in = storage.open(key)) {
                    byte[] seen = in.readAllBytes();
                    if (seen.length != MIB || !(isAll(seen, (byte) 'a') || isAll(seen, (byte) 'b'))) {
                        torn.compareAndSet(null, "a read of " + seen.length + " mixed or partial bytes");
                    }
                    reads.incrementAndGet();
                } catch (IOException failure) {
                    torn.compareAndSet(null, failure.toString());
                }
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 40; i++) {
                storage.put(key, i % 2 == 0 ? b : a, "image/jpeg", WriteMode.REPLACE);
            }
        } finally {
            done.set(true);
            reader.join(30_000);
        }
        assertThat(torn.get()).isNull();
        assertThat(reads.get()).isPositive();
        assertThat(isAll(Files.readAllBytes(dir.resolve(key)), (byte) 'a')).isTrue();
        assertThat(temporaryFiles()).isEmpty();
    }

    @Test
    void filesAreReadableAndNotExecutable() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX file system");
        // files start 0600 and folders 0700, so these modes hold only because they are set, whatever the umask
        storage.put("2026/11/mode.jpg", file("x"), "image/jpeg", WriteMode.CREATE_NEW);
        storage.put("2026/11/mode-1.jpg", file("x"), "image/jpeg", WriteMode.REPLACE);
        new FsMediaStorage(dir, Optional.empty(), "/u", true)
                .put("2026/10/mode.jpg", file("x"), "image/jpeg", WriteMode.CREATE_NEW);
        for (String key : List.of("2026/11/mode.jpg", "2026/11/mode-1.jpg", "2026/10/mode.jpg")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(key))))
                    .as(key)
                    .isEqualTo("rw-r--r--");
        }
        for (String folder : List.of("2026", "2026/11", "2026/10")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(folder))))
                    .as(folder)
                    .isEqualTo("rwxr-xr-x");
        }
    }

    @Test
    void listNamesTheFolderEntriesOnly() throws Exception {
        for (String key : List.of("2026/11/a.jpg", "2026/11/a-150x150.jpg", "2026/11/sub/x.jpg", "2026/10/b.jpg")) {
            storage.put(key, file(key), "image/jpeg", WriteMode.CREATE_NEW);
        }
        assertThat(storage.list("2026/11")).containsExactlyInAnyOrder("a.jpg", "a-150x150.jpg", "sub");
        assertThat(storage.list("2025/01")).isEmpty();
        assertThat(storage.list("2026/11/a.jpg")).isEmpty();
    }

    @Test
    void readsSizesAndDeletes() throws Exception {
        storage.put("2026/11/s.jpg", file("12345"), "image/jpeg", WriteMode.CREATE_NEW);
        assertThat(storage.exists("2026/11/s.jpg")).isTrue();
        assertThat(storage.exists("2026/11")).isFalse();
        assertThat(storage.size("2026/11/s.jpg")).isEqualTo(OptionalLong.of(5));
        assertThat(storage.size("2026/11/none.jpg")).isEmpty();
        assertThatThrownBy(() -> storage.open("2026/11/none.jpg"))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
        assertThatThrownBy(() -> storage.open("2026/11")).isInstanceOf(java.nio.file.NoSuchFileException.class);
        assertThat(storage.delete("2026/11")).isFalse();
        assertThat(storage.delete("2026/11/s.jpg")).isTrue();
        assertThat(storage.exists("2026/11/s.jpg")).isFalse();
    }

    @Test
    void deleteToleratesMissingKeys() throws Exception {
        assertThat(storage.delete("2026/11/none.jpg")).isFalse();
    }

    @Test
    void aMissingDirectoryIsCreatedOnTheFirstWrite() throws Exception {
        FsMediaStorage fresh = new FsMediaStorage(base.resolve("later/uploads"), Optional.empty(), "/u");
        assertThat(fresh.exists("2026/11/a.jpg")).isFalse();
        assertThat(fresh.list("")).isEmpty();
        assertThat(fresh.delete("2026/11/a.jpg")).isFalse();
        fresh.put("2026/11/a.jpg", file("a"), "image/jpeg", WriteMode.CREATE_NEW);
        assertThat(fresh.exists("2026/11/a.jpg")).isTrue();
    }

    @Test
    void keysOutsideTheDirectoryAreRefused() throws Exception {
        for (String key : List.of("../x", "a/../../x", "/etc/passwd", "a//b", "a\\b", "a/./b", "a\u0000b", "", "..")) {
            assertThatThrownBy(() -> storage.exists(key)).as(key).isInstanceOf(IllegalArgumentException.class);
        }
        Path outside = Files.createDirectories(base.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(dir.resolve("escape"), outside);
            Files.createSymbolicLink(dir.resolve("secret.txt"), outside.resolve("secret.txt"));
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            assumeTrue(false, "symbolic links unsupported: " + unsupported);
        }
        for (String key : List.of("escape/secret.txt", "escape/new.txt", "escape/sub/new.txt", "secret.txt")) {
            assertThatThrownBy(() -> storage.exists(key)).as(key).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.open(key)).as(key).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.size(key)).as(key).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.put(key, file("evil"), "text/plain", WriteMode.CREATE_NEW))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.put(key, file("evil"), "text/plain", WriteMode.REPLACE))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.delete(key)).as(key).isInstanceOf(IllegalArgumentException.class);
            assertThat(storage.servable(key)).as(key).isEmpty();
        }
        assertThatThrownBy(() -> storage.list("escape")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(outside.resolve("secret.txt"))).isEqualTo("secret");
        try (var entries = Files.list(outside)) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("secret.txt");
        }
    }

    @Test
    void anyFailureOfTheLinkButATakenNameTakesTheFallback() throws Exception {
        AtomicInteger links = new AtomicInteger();
        FsMediaStorage noLinks = new FsMediaStorage(dir, Optional.empty(), "/wp-content/uploads") {
            @Override
            void createLink(Path target, Path temporary) throws IOException {
                links.incrementAndGet();
                // an error text in another language: never matched, any failure means the way without links
                throw new FileSystemException(target.toString(), temporary.toString(), "Vorgang nicht zul\u00e4ssig");
            }
        };
        noLinks.put("2026/11/fallback.jpg", file("one"), "image/jpeg", WriteMode.CREATE_NEW);
        assertThat(links.get()).isEqualTo(1);
        assertThat(read("2026/11/fallback.jpg")).isEqualTo("one");
        assertThatThrownBy(() -> noLinks.put("2026/11/fallback.jpg", file("two"), "image/jpeg", WriteMode.CREATE_NEW))
                .isInstanceOf(KeyTakenException.class);
        assertThat(read("2026/11/fallback.jpg")).isEqualTo("one");
        assertThat(temporaryFiles()).isEmpty();

        FsMediaStorage unsupported = new FsMediaStorage(dir, Optional.empty(), "/wp-content/uploads") {
            @Override
            void createLink(Path target, Path temporary) {
                throw new UnsupportedOperationException("no hard links");
            }
        };
        unsupported.put("2026/11/unsupported.jpg", file("u"), "image/jpeg", WriteMode.CREATE_NEW);
        assertThat(read("2026/11/unsupported.jpg")).isEqualTo("u");

        FsMediaStorage taken = new FsMediaStorage(dir, Optional.empty(), "/wp-content/uploads") {
            @Override
            void createLink(Path target, Path temporary) throws IOException {
                throw new FileAlreadyExistsException(target.toString());
            }
        };
        assertThatThrownBy(() -> taken.put("2026/11/taken.jpg", file("t"), "image/jpeg", WriteMode.CREATE_NEW))
                .isInstanceOf(KeyTakenException.class);
        assertThat(dir.resolve("2026/11/taken.jpg")).doesNotExist();
        assertThat(temporaryFiles()).isEmpty();
    }

    @Test
    void aSymbolicLinkIsNoStoredFile() throws Exception {
        storage.put("2026/11/real.jpg", file("real"), "image/jpeg", WriteMode.CREATE_NEW);
        try {
            Files.createSymbolicLink(dir.resolve("2026/11/link.jpg"), dir.resolve("2026/11/real.jpg"));
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            assumeTrue(false, "symbolic links unsupported: " + unsupported);
        }
        assertThat(storage.exists("2026/11/link.jpg")).isFalse();
        assertThat(storage.size("2026/11/link.jpg")).isEmpty();
        assertThat(storage.servable("2026/11/link.jpg")).isEmpty();
        assertThatThrownBy(() -> storage.open("2026/11/link.jpg"))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
        assertThat(storage.delete("2026/11/link.jpg")).isFalse();
        assertThat(storage.servable("2026/11/real.jpg")).hasValueSatisfying(served -> {
            assertThat(served.size()).isEqualTo(4);
            assertThat(served.file()).isEqualTo(dir.resolve("2026/11/real.jpg"));
        });
    }

    @Test
    void publicUrls() {
        SiteInfo sub = site("localhost", "/site3/");
        assertThat(new FsMediaStorage(dir, Optional.empty(), "/wp-content/uploads")
                        .publicUrl(sub, "sites/3/2026/11/photo.jpg"))
                .isEqualTo("http://localhost/site3/wp-content/uploads/sites/3/2026/11/photo.jpg");
        assertThat(new FsMediaStorage(dir, Optional.of("https://cdn.example.test/u"), "/wp-content/uploads")
                        .publicUrl(sub, "sites/3/2026/11/" + cp(0x424) + ".jpg"))
                .isEqualTo("https://cdn.example.test/u/sites/3/2026/11/" + cp(0x424) + ".jpg");
        assertThat(new FsMediaStorage(dir, Optional.of("https://cdn.example.test/u/"), "/x")
                        .publicUrl(site("a.test", "/"), "2026/11/a b.jpg"))
                .isEqualTo("https://cdn.example.test/u/2026/11/a b.jpg");
        assertThat(new FsMediaStorage(dir, Optional.empty(), "/media/").publicUrl(site("a.test", "/"), "2026/11/a.jpg"))
                .isEqualTo("http://a.test/media/2026/11/a.jpg");
        assertThatThrownBy(() -> storage.publicUrl(sub, "../x")).isInstanceOf(IllegalArgumentException.class);
    }

    private static SiteInfo site(String domain, String path) {
        return new SiteInfo(SiteId.of(3), domain, path, "Site", 1, false, false, false, false);
    }

    private static String cp(int codePoint) {
        return Character.toString(codePoint);
    }

    private Path file(String content) throws IOException {
        return Files.writeString(base.resolve("source-" + UUID.randomUUID()), content);
    }

    private Path bytes(char c, int length) throws IOException {
        byte[] content = new byte[length];
        Arrays.fill(content, (byte) c);
        return Files.write(base.resolve("source-" + UUID.randomUUID()), content);
    }

    private String read(String key) throws IOException {
        try (InputStream in = storage.open(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Path> temporaryFiles() throws IOException {
        Path temporary = dir.resolve(".tmp");
        if (!Files.isDirectory(temporary)) {
            return List.of();
        }
        try (var entries = Files.list(temporary)) {
            return entries.toList();
        }
    }

    private static boolean isAll(byte[] content, byte value) {
        for (byte b : content) {
            if (b != value) {
                return false;
            }
        }
        return true;
    }
}
