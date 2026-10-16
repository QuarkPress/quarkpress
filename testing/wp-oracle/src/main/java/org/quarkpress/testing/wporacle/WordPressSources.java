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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        String root = System.getProperty("quarkpress.root");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("quarkpress.root is not set; run with -Pwp-parity");
        }
        return Path.of(root);
    }

    @SuppressWarnings("try")
    static Path ensure(WordPressRelease release, Path dir, URI base, List<String> files)
            throws IOException, InterruptedException {
        Set<String> wanted = new LinkedHashSet<>(BOOTSTRAP_FILES);
        wanted.addAll(files);
        for (String path : wanted) {
            if (!release.sha256ByPath().containsKey(path)) {
                throw new IllegalArgumentException("no SHA-256 pin for " + path + " in WordPress " + release.version());
            }
        }
        Files.createDirectories(dir);
        Instant deadline = Instant.now().plus(TOTAL_TIMEOUT);
        StringBuilder marker = new StringBuilder();
        // modules build in parallel (-T4): one process at a time may download into the shared directory
        try (FileChannel lockChannel =
                        FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = lockChannel.lock();
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(PER_FILE_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()) {
            for (String path : wanted) {
                String expected = release.sha256ByPath().get(path);
                Path target = dir.resolve(path);
                if (!Files.isRegularFile(target) || !expected.equals(sha256(target))) {
                    download(client, base.resolve(release.tag() + "/src/" + path), target, deadline);
                    String actual = sha256(target);
                    if (!expected.equals(actual)) {
                        Files.delete(target);
                        throw new IOException("SHA-256 mismatch for " + path + ": expected " + expected + ", got "
                                + actual + "; the file was deleted");
                    }
                }
                marker.append(expected).append("  ").append(path).append('\n');
            }
        }
        Files.writeString(dir.resolve(MARKER), marker);
        return dir;
    }

    private static void download(HttpClient client, URI uri, Path target, Instant deadline)
            throws IOException, InterruptedException {
        Duration left = Duration.between(Instant.now(), deadline);
        if (left.isNegative() || left.isZero()) {
            throw new IOException("downloading WordPress files took longer than " + TOTAL_TIMEOUT.toMinutes()
                    + " minutes; stopped before " + uri);
        }
        Duration timeout = left.compareTo(PER_FILE_TIMEOUT) < 0 ? left : PER_FILE_TIMEOUT;
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        CompletableFuture<HttpResponse<Path>> pending =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(partial));
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
