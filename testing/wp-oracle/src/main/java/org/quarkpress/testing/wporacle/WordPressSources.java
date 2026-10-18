package org.quarkpress.testing.wporacle;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Downloads WordPress source files of a pinned release into {@code target/wp-source/<version>/} of the project
 * root, verifies each against its SHA-256 pin, and reuses verified files on later runs. The files are a
 * test-time input only: never committed, never shipped. A file lock in that directory keeps modules that build
 * in parallel from downloading the same file at once.
 */
public final class WordPressSources {

    public static final URI DEFAULT_BASE = URI.create("https://raw.githubusercontent.com/WordPress/wordpress-develop/");

    /** Files that {@code bootstrap.php} loads for every driver. */
    public static final List<String> BOOTSTRAP_FILES = List.of(
            "wp-includes/plugin.php", "wp-includes/class-wp-hook.php", "wp-includes/class-wp-filter-sentinel.php");

    static final Duration PER_FILE_TIMEOUT = Duration.ofSeconds(30);
    static final Duration TOTAL_TIMEOUT = Duration.ofMinutes(10);

    /** Per-file limit for a release archive, which is tens of megabytes. */
    static final Duration ARCHIVE_TIMEOUT = Duration.ofMinutes(5);

    static final String MARKER = ".verified";
    static final String LOCK = ".lock";

    private WordPressSources() {}

    /**
     * Makes sure the bootstrap files and {@code files} (paths relative to {@code wordpress-develop/src/}) are
     * present and verified, and returns the directory that holds them.
     */
    public static Path ensure(WordPressRelease release, String... files) throws IOException, InterruptedException {
        Path dir = projectRoot().resolve("target").resolve("wp-source").resolve(release.version());
        return ensure(release, dir, DEFAULT_BASE, List.of(files));
    }

    static Path projectRoot() {
        String root = System.getProperty("quarkpressRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("quarkpressRoot is not set; run with -Pwp-parity");
        }
        return Path.of(root);
    }

    /**
     * Makes sure {@code files} (paths relative to the repository root) of a pinned non-WordPress repository are
     * present and verified in {@code target/wp-source/<name>/}, and returns that directory.
     */
    public static Path ensure(PinnedRepository repository, String... files) throws IOException, InterruptedException {
        Path dir = projectRoot().resolve("target").resolve("wp-source").resolve(repository.name());
        return ensure(repository, dir, List.of(files));
    }

    static Path ensure(PinnedRepository repository, Path dir, List<String> files)
            throws IOException, InterruptedException {
        return fetch(
                repository.sha256ByPath(),
                repository.name(),
                repository::uri,
                dir,
                new LinkedHashSet<>(files),
                PER_FILE_TIMEOUT);
    }

    /**
     * Makes sure the pinned file is present and verified in {@code target/wp-source/<name>/} of the project root, and
     * returns it.
     */
    public static Path ensure(PinnedDownload download) throws IOException, InterruptedException {
        Path dir = projectRoot().resolve("target").resolve("wp-source").resolve(download.name());
        return ensure(download, dir);
    }

    static Path ensure(PinnedDownload download, Path dir) throws IOException, InterruptedException {
        fetch(
                Map.of(download.fileName(), download.sha256()),
                download.name(),
                path -> download.uri(),
                dir,
                new LinkedHashSet<>(List.of(download.fileName())),
                ARCHIVE_TIMEOUT);
        return dir.resolve(download.fileName());
    }

    static Path ensure(WordPressRelease release, Path dir, URI base, List<String> files)
            throws IOException, InterruptedException {
        Set<String> wanted = new LinkedHashSet<>(BOOTSTRAP_FILES);
        wanted.addAll(files);
        return fetch(
                release.sha256ByPath(),
                "WordPress " + release.version(),
                path -> base.resolve(release.tag() + "/src/" + path),
                dir,
                wanted,
                PER_FILE_TIMEOUT);
    }

    @SuppressWarnings("try")
    private static Path fetch(
            Map<String, String> pins,
            String what,
            Function<String, URI> uriOf,
            Path dir,
            Set<String> wanted,
            Duration perFile)
            throws IOException, InterruptedException {
        for (String path : wanted) {
            if (!pins.containsKey(path)) {
                throw new IllegalArgumentException("no SHA-256 pin for " + path + " in " + what);
            }
        }
        Files.createDirectories(dir);
        Instant deadline = Instant.now().plus(TOTAL_TIMEOUT);
        StringBuilder marker = new StringBuilder();
        // modules build in parallel: one thread and one process at a time may download into the shared directory
        ReentrantLock threads = PathLock.of(dir.resolve(LOCK));
        threads.lock();
        try (FileChannel lockChannel =
                        FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = lockChannel.lock();
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(PER_FILE_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()) {
            for (String path : wanted) {
                String expected = pins.get(path);
                Path target = dir.resolve(path);
                if (!Files.isRegularFile(target) || !expected.equals(sha256(target))) {
                    download(client, uriOf.apply(path), target, deadline, perFile, path, expected);
                }
                marker.append(expected).append("  ").append(path).append('\n');
            }
        } finally {
            threads.unlock();
        }
        Files.writeString(dir.resolve(MARKER), marker);
        return dir;
    }

    private static void download(
            HttpClient client, URI uri, Path target, Instant deadline, Duration perFile, String path, String expected)
            throws IOException, InterruptedException {
        Duration left = Duration.between(Instant.now(), deadline);
        if (left.isNegative() || left.isZero()) {
            throw new IOException("downloading WordPress files took longer than " + TOTAL_TIMEOUT.toMinutes()
                    + " minutes; stopped before " + uri);
        }
        Duration timeout = left.compareTo(perFile) < 0 ? left : perFile;
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        CompletableFuture<HttpResponse<Path>> pending = client.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofFile(
                        partial,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));
        HttpResponse<Path> response;
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            Files.deleteIfExists(partial);
            throw new IOException("timed out after " + timeout.toSeconds() + " s downloading " + uri);
        } catch (ExecutionException e) {
            Files.deleteIfExists(partial);
            throw new IOException("cannot download " + uri + ": " + e.getCause(), e.getCause());
        }
        if (response.statusCode() != 200) {
            Files.deleteIfExists(partial);
            throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        String actual = sha256(partial);
        if (!expected.equals(actual)) {
            Files.delete(partial);
            throw new IOException("SHA-256 mismatch for " + path + ": expected " + expected + ", got " + actual
                    + "; the file was deleted");
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
