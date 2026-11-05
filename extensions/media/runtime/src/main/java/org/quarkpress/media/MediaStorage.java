package org.quarkpress.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.quarkpress.core.SiteInfo;

/**
 * Where the files of the media library are kept: a storage backend module provides one bean of this type (named with
 * {@code @Identifier}), or the application does. Keys are relative paths of {@code /}-separated segments; every method
 * refuses an invalid key with {@link IllegalArgumentException} (see {@link StoredKey#require}) before it touches the
 * storage.
 */
public interface MediaStorage {

    /**
     * Stores a copy of {@code source} under {@code key}.
     *
     * @param mimeType the file's type, for backends that keep it with the file
     * @param mode {@link WriteMode#CREATE_NEW} throws {@link KeyTakenException} when the key holds a file and never
     *     replaces it; {@link WriteMode#REPLACE} replaces it in one step
     */
    void put(String key, Path source, String mimeType, WriteMode mode) throws IOException;

    /**
     * Opens the file under {@code key} for reading; the caller closes the stream.
     *
     * @throws NoSuchFileException when the key holds no file
     */
    InputStream open(String key) throws IOException;

    /** Whether {@code key} holds a file. */
    boolean exists(String key);

    /**
     * The names of the direct entries of the folder {@code folderKey}, files and folders alike, without {@code .} and
     * {@code ..}, in no particular order; empty when the folder does not exist.
     */
    List<String> list(String folderKey);

    /** Removes the file under {@code key}: false when there was none (a missing key never throws). */
    boolean delete(String key);

    /** The size of the file under {@code key} in bytes, or empty when there is none. */
    OptionalLong size(String key);

    /** The public URL of the file under {@code key} for {@code site}; the key's characters are used as they are. */
    String publicUrl(SiteInfo site, String key);
}
