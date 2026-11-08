package org.quarkpress.media.storage.fs.runtime;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.KeyTakenException;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.StoredKey;
import org.quarkpress.media.WriteMode;

/**
 * Keeps media files in a directory: a key such as {@code 2026/11/photo.jpg} is the file of that relative path. No key
 * reaches outside the directory, neither through its segments nor through a symbolic link inside it.
 *
 * <p>A file is first written to a temporary file in the directory's {@code .tmp} folder (on the same file system as
 * the stored files, out of every key's reach and never listed), made readable by all and writable by its owner, then
 * put in place in one step: a new file by a hard link, which fails when the name is taken, so a file is never
 * replaced by a concurrent writer of the same name; where a hard link cannot be made (a file system without them, or
 * any other failure of the link but a taken name), by reserving the name with an exclusively created empty file and
 * moving the temporary file over that placeholder. A replacing write moves
 * the temporary file over the old one, so a reader sees the old file or the new one, never a part.
 *
 * <p>On that way, the reserved name holds an empty file until the move: a reader may see it empty for that
 * moment, and a crash in between leaves the empty file behind, keeping the name taken (uploads then choose another
 * name, so no upload is lost).
 *
 * <p>The last segment of a key is never followed when it is a symbolic link: a link is no stored file.
 */
@ApplicationScoped
@Identifier("fs")
public class FsMediaStorage implements MediaStorage {

    /** The folder of the temporary files, below the directory. */
    static final String TEMPORARY = ".tmp";

    /** How old a temporary file is when the start-up sweep removes it. */
    static final Duration STALE = Duration.ofHours(1);

    static final String DIRECTORY_REQUIRED = "quarkpress.media.fs.directory is required by quarkpress-media-storage-fs";

    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-r--r--");
    private static final Set<PosixFilePermission> PRIVATE_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> FOLDER = PosixFilePermissions.fromString("rwxr-xr-x");
    private static final Set<PosixFilePermission> PRIVATE_FOLDER = PosixFilePermissions.fromString("rwx------");

    private final Path directory;
    private final Optional<String> baseUrl;
    private final String path;
    private final boolean forceFallback;

    @Inject
    public FsMediaStorage(FsConfig config) {
        this(
                config.directory().orElseThrow(() -> new IllegalStateException(DIRECTORY_REQUIRED)),
                config.baseUrl(),
                config.path());
    }

    /**
     * A storage over {@code directory}.
     *
     * @param baseUrl the URL the files are published under, else the site's address followed by {@code path}
     * @param path the URL path the files are served under, such as {@code /wp-content/uploads}
     */
    public FsMediaStorage(Path directory, Optional<String> baseUrl, String path) {
        this(directory, baseUrl, path, false);
    }

    /** As the public constructor; with {@code forceFallback}, new files are never put in place by a hard link. */
    FsMediaStorage(Path directory, Optional<String> baseUrl, String path, boolean forceFallback) {
        this.directory = directory.toAbsolutePath().normalize();
        this.baseUrl = baseUrl;
        this.path = path;
        this.forceFallback = forceFallback;
    }

    /** The directory that holds the files. */
    public Path directory() {
        return directory;
    }

    @Override
    public void put(String key, Path source, String mimeType, WriteMode mode) throws IOException {
        Path target = resolve(key);
        createFolders(target.getParent());
        target = resolve(key);
        Path temporary = temporaryFile();
        try {
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE);
                    FileChannel in = FileChannel.open(source, StandardOpenOption.READ)) {
                long size = in.size();
                long done = 0;
                while (done < size) {
                    long copied = in.transferTo(done, size - done, out);
                    if (copied <= 0) {
                        break;
                    }
                    done += copied;
                }
                out.force(true);
            }
            setPermissions(temporary, FILE);
            if (mode == WriteMode.REPLACE) {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else if (forceFallback || !link(key, target, temporary)) {
                reserveAndMove(key, target, temporary);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Links {@code target} to {@code temporary}: false when the link could not be made for any reason but a taken
     * name (no hard links on this file system, or any other failure), so the caller takes the way without links,
     * which still never replaces a file.
     */
    private boolean link(String key, Path target, Path temporary) throws IOException {
        try {
            createLink(target, temporary);
            return true;
        } catch (FileAlreadyExistsException taken) {
            throw new KeyTakenException(key);
        } catch (IOException | UnsupportedOperationException failed) {
            return false;
        }
    }

    /** Makes {@code target} a hard link to {@code temporary}; a test replaces it to simulate a file system. */
    void createLink(Path target, Path temporary) throws IOException {
        Files.createLink(target, temporary);
    }

    /**
     * Puts a new file in place without hard links: an exclusively created empty file reserves the name (failing when
     * it is taken), then the temporary file is moved over that own placeholder.
     */
    private void reserveAndMove(String key, Path target, Path temporary) throws IOException {
        try {
            Files.createFile(target);
        } catch (FileAlreadyExistsException taken) {
            throw new KeyTakenException(key);
        }
        boolean moved = false;
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(target);
            }
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        Path file = resolve(key);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(key);
        }
        return Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public List<String> list(String folderKey) {
        Path folder = folderKey.isEmpty() ? directory : resolve(folderKey);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!(folderKey.isEmpty() && name.equals(TEMPORARY))) {
                    names.add(name);
                }
            }
        } catch (NoSuchFileException gone) {
            return List.of();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return names;
    }

    @Override
    public boolean delete(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return Files.deleteIfExists(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public OptionalLong size(String key) {
        Path file = resolve(key);
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() ? OptionalLong.of(attributes.size()) : OptionalLong.empty();
        } catch (NoSuchFileException gone) {
            return OptionalLong.empty();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public String publicUrl(SiteInfo site, String key) {
        StoredKey.require(key);
        if (baseUrl.isPresent()) {
            return withoutTrailingSlashes(baseUrl.get()) + "/" + key;
        }
        String siteUrl = site.url("http").toString();
        String served = withoutSlashes(path);
        return served.isEmpty() ? siteUrl + key : siteUrl + served + "/" + key;
    }

    /**
     * A stored file opened for serving: the channel reads the file that was checked, whatever happens to its name
     * afterwards. The caller closes it.
     */
    record Served(SeekableByteChannel channel, long size, Instant lastModified) implements Closeable {

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    /**
     * The file a key names, opened for serving: empty when the key is invalid, names a temporary file, or names no
     * regular file reached without a symbolic link. No segment of the key is followed when it is a link: each folder
     * is opened relative to the one before it, and the file relative to its folder, without following links, so what
     * is opened is always below the directory. Where the file system cannot open relative to a folder, each segment is
     * checked first and the file opened after, a gap in which a link put in a folder's place would be followed.
     */
    Optional<Served> servable(String key) {
        try {
            Path relative = directory.relativize(resolve(key));
            try (DirectoryStream<Path> top = Files.newDirectoryStream(directory)) {
                if (top instanceof SecureDirectoryStream<Path> secure) {
                    return servable(secure, relative);
                }
            }
            return servableByPath(relative);
        } catch (IllegalArgumentException | IOException refusedOrMissing) {
            return Optional.empty();
        }
    }

    private static Optional<Served> servable(SecureDirectoryStream<Path> top, Path relative) throws IOException {
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        try {
            SecureDirectoryStream<Path> folder = top;
            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                folder = folder.newDirectoryStream(relative.getName(i), LinkOption.NOFOLLOW_LINKS);
                opened.add(folder);
            }
            Path name = relative.getFileName();
            BasicFileAttributes attributes = folder.getFileAttributeView(
                            name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
            if (!attributes.isRegularFile()) {
                return Optional.empty();
            }
            return Optional.of(served(
                    folder.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)),
                    attributes));
        } finally {
            for (SecureDirectoryStream<Path> folder : opened) {
                folder.close();
            }
        }
    }

    private Optional<Served> servableByPath(Path relative) throws IOException {
        Path current = directory;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return Optional.empty();
            }
        }
        BasicFileAttributes attributes =
                Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            return Optional.empty();
        }
        return Optional.of(
                served(Files.newByteChannel(current, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), attributes));
    }

    /** The opened file with its size (that of the open file) and the modification time read before opening it. */
    private static Served served(SeekableByteChannel channel, BasicFileAttributes attributes) throws IOException {
        try {
            return new Served(
                    channel, channel.size(), attributes.lastModifiedTime().toInstant());
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    /**
     * Removes the temporary files older than an hour before {@code now}: those left by an interrupted write.
     *
     * @return how many were removed
     */
    public int sweep(Instant now) {
        Path folder = directory.resolve(TEMPORARY);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        Instant limit = now.minus(STALE);
        int removed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                try {
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                            && Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)
                                    .toInstant()
                                    .isBefore(limit)
                            && Files.deleteIfExists(entry)) {
                        removed++;
                    }
                } catch (NoSuchFileException gone) {
                    // removed meanwhile by the write that made it
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return removed;
    }

    /**
     * The file of a key, after checking that it stays inside the directory: by its segments, and by the real path of
     * its deepest existing ancestor (so not through a symbolic link that leads out). The temporary folder is no key's.
     */
    Path resolve(String key) {
        StoredKey.require(key);
        if (key.equals(TEMPORARY) || key.startsWith(TEMPORARY + "/")) {
            throw new IllegalArgumentException("a storage key does not name the temporary folder: " + key);
        }
        Path file = directory.resolve(key).normalize();
        if (!file.startsWith(directory) || file.equals(directory)) {
            throw new IllegalArgumentException("a storage key stays inside the storage directory: " + key);
        }
        Path existing = file;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.startsWith(directory)) {
            // the directory itself does not exist yet: nothing below it can lead out
            return file;
        }
        try {
            Path real = existing.toRealPath();
            Path realDirectory = directory.toRealPath();
            if (!real.startsWith(realDirectory)) {
                throw new IllegalArgumentException("a storage key stays inside the storage directory: " + key);
            }
        } catch (IOException unresolvable) {
            throw new IllegalArgumentException("a storage key stays inside the storage directory: " + key);
        }
        return file;
    }

    /** Creates the missing folders down to {@code folder}, each readable by all and writable by its owner. */
    private void createFolders(Path folder) throws IOException {
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory);
        }
        List<Path> missing = new ArrayList<>();
        for (Path current = folder;
                current != null && !current.equals(directory) && current.startsWith(directory);
                current = current.getParent()) {
            if (Files.isDirectory(current)) {
                break;
            }
            missing.add(current);
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            createFolder(missing.get(i), FOLDER);
        }
    }

    /** Creates a folder owner-only, then gives it {@code permissions}, so the result never depends on the umask. */
    private static void createFolder(Path folder, Set<PosixFilePermission> permissions) throws IOException {
        try {
            if (posix(folder.getParent())) {
                Files.createDirectory(folder, permissionsAttribute(PRIVATE_FOLDER));
            } else {
                Files.createDirectory(folder);
            }
        } catch (FileAlreadyExistsException present) {
            if (Files.isDirectory(folder)) {
                return;
            }
            throw present;
        }
        setPermissions(folder, permissions);
    }

    /**
     * A new empty temporary file in the temporary folder, readable and writable by its owner only: on the file system
     * of the stored files, out of every key's reach and never listed. The start-up sweep removes those left there for
     * more than an hour.
     */
    @Override
    public Path temporaryFile() throws IOException {
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory);
        }
        Path folder = directory.resolve(TEMPORARY);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            createFolder(folder, PRIVATE_FOLDER);
        }
        Path file = folder.resolve(UUID.randomUUID() + ".tmp");
        if (posix(folder)) {
            Files.createFile(file, permissionsAttribute(PRIVATE_FILE));
            setPermissions(file, PRIVATE_FILE);
        } else {
            Files.createFile(file);
        }
        return file;
    }

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** Sets {@code permissions} where the file system has POSIX permissions, whatever the process's umask. */
    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        if (posix(path)) {
            Files.setPosixFilePermissions(path, permissions);
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> permissionsAttribute(Set<PosixFilePermission> permissions) {
        return PosixFilePermissions.asFileAttribute(permissions);
    }

    private static String withoutTrailingSlashes(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '/') {
            end--;
        }
        return text.substring(0, end);
    }

    private static String withoutSlashes(String text) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == '/') {
            start++;
        }
        return withoutTrailingSlashes(text.substring(start));
    }
}
