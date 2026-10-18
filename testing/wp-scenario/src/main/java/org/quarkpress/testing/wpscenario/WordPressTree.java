package org.quarkpress.testing.wpscenario;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.quarkpress.testing.wporacle.PathLock;
import org.quarkpress.testing.wporacle.PinnedDownload;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * The WordPress release the scenarios install: the archive pinned in testing/wp-oracle as
 * {@code wordpress-<version>-release}, downloaded and SHA-256 verified there, then unpacked under
 * {@code target/wp-scenario/<version>/} of the project root. Neither the archive nor the tree is ever committed.
 */
public final class WordPressTree {

    static final String MARKER = ".unpacked";
    static final String LOCK = ".lock";
    static final int MAX_ENTRIES = 20_000;
    static final long MAX_BYTES = 512L * 1024 * 1024;

    private WordPressTree() {}

    /** The {@code wordpress/} directory of the unpacked release {@code version}; downloads and unpacks it first. */
    public static Path ensure(String version) throws IOException, InterruptedException {
        PinnedDownload pin = PinnedDownload.load("wordpress-" + version + "-release");
        Path archive = WordPressSources.ensure(pin);
        return unpack(
                archive,
                pin.sha256(),
                projectRoot().resolve("target").resolve("wp-scenario").resolve(version));
    }

    static Path projectRoot() {
        String root = System.getProperty("quarkpressRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("quarkpressRoot is not set; run with -Pwp-parity");
        }
        return Path.of(root);
    }

    /**
     * Unpacks {@code archive} into {@code dir} unless the marker shows that this archive is already unpacked there, and
     * returns {@code dir/wordpress}. A file lock keeps parallel builds from unpacking at once.
     */
    @SuppressWarnings("try")
    static Path unpack(Path archive, String sha256, Path dir) throws IOException {
        Files.createDirectories(dir);
        ReentrantLock threads = PathLock.of(dir.resolve(LOCK));
        threads.lock();
        try (FileChannel channel =
                        FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            Path marker = dir.resolve(MARKER);
            Path tree = dir.resolve("wordpress");
            if (Files.isRegularFile(marker)
                    && Files.readString(marker).strip().equals(sha256)
                    && Files.isDirectory(tree)) {
                return tree;
            }
            Files.deleteIfExists(marker);
            deleteTree(tree);
            extract(archive, dir);
            if (!Files.isRegularFile(tree.resolve("wp-settings.php"))) {
                throw new IOException("the archive " + archive + " holds no wordpress/wp-settings.php");
            }
            Files.writeString(marker, sha256 + "\n");
            return tree;
        } finally {
            threads.unlock();
        }
    }

    /** Extracts every entry of a zip archive below {@code dir}; an entry that would land outside it fails. */
    static void extract(Path archive, Path dir) throws IOException {
        extract(archive, dir, MAX_ENTRIES, MAX_BYTES);
    }

    /** As {@link #extract(Path, Path)} with explicit limits on the entry count and the total uncompressed size. */
    static void extract(Path archive, Path dir, int maxEntries, long maxBytes) throws IOException {
        int entries = 0;
        long bytes = 0;
        byte[] buffer = new byte[64 * 1024];
        Path root = dir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > maxEntries) {
                    throw new IOException("the archive " + archive + " holds more than " + maxEntries + " entries");
                }
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root) || target.equals(root)) {
                    throw new IOException("the archive entry " + entry.getName() + " lies outside " + root);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        for (int n = zip.read(buffer); n >= 0; n = zip.read(buffer)) {
                            bytes += n;
                            if (bytes > maxBytes) {
                                throw new IOException(
                                        "the archive " + archive + " unpacks to more than " + maxBytes + " bytes");
                            }
                            out.write(buffer, 0, n);
                        }
                    }
                }
            }
        }
    }

    private static void deleteTree(Path tree) throws IOException {
        if (!Files.exists(tree)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(tree)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }
}
