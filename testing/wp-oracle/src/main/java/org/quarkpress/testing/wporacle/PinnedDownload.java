package org.quarkpress.testing.wporacle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * One file downloaded from a fixed URL and checked against its SHA-256, such as a WordPress release archive. Pins come
 * from the classpath resource {@code <name>.sha256} next to this class: a {@code # url <uri>} line and exactly one
 * {@code <sha256>  <file name>} line; the file name is where the download is stored.
 */
public record PinnedDownload(String name, URI uri, String fileName, String sha256) {

    private static final String URL_PREFIX = "# url ";

    public PinnedDownload {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(sha256, "sha256");
        if (!plain(name) || name.startsWith(".")) {
            throw new IllegalArgumentException("the name of a pinned download must be a plain name: " + name);
        }
        if (!plain(fileName)
                || fileName.equals(WordPressSources.LOCK)
                || fileName.equals(WordPressSources.MARKER)
                || fileName.endsWith(".part")) {
            throw new IllegalArgumentException("the file name of " + name + " must be a plain file name: " + fileName);
        }
        if (!PinnedRepository.isLowerHex(sha256, 64)) {
            throw new IllegalArgumentException("the SHA-256 of " + name + " must be 64 lower-case hex digits");
        }
    }

    private static boolean plain(String text) {
        return !text.isEmpty()
                && text.indexOf('/') < 0
                && text.indexOf('\\') < 0
                && !text.contains("..")
                && text.indexOf('\0') < 0;
    }

    /** Loads the pin in {@code <name>.sha256}. */
    public static PinnedDownload load(String name) {
        String resource = name + ".sha256";
        try (InputStream in = PinnedDownload.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no pin file " + resource);
            }
            return parse(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static PinnedDownload parse(String name, String text) {
        String url = null;
        String sha256 = null;
        String fileName = null;
        int pins = 0;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith(URL_PREFIX)) {
                url = line.substring(URL_PREFIX.length()).strip();
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            if (parts.length != 2 || !PinnedRepository.isLowerHex(parts[0], 64)) {
                throw new IllegalArgumentException("pin line must be '<sha256>  <file name>': " + line);
            }
            sha256 = parts[0];
            fileName = parts[1];
            pins++;
        }
        if (url == null) {
            throw new IllegalArgumentException("pin file " + name + " lacks a '# url <uri>' line");
        }
        if (pins != 1) {
            throw new IllegalArgumentException("pin file " + name + " must pin exactly one file");
        }
        return new PinnedDownload(name, URI.create(url), fileName, sha256);
    }
}
