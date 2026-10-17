package org.quarkpress.testing.wporacle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A repository other than wordpress-develop, pinned at one commit, with the SHA-256 of every file the oracle may
 * download from it. Pins come from the classpath resource {@code <name>.sha256} next to this class, which starts
 * with {@code # source <base-uri>} (ending in {@code /}) and {@code # ref <40-hex commit>}; each other
 * non-comment line is {@code <sha256>  <path relative to the repository root>}. A file is downloaded from
 * {@code <base-uri><ref>/<path>}.
 */
public record PinnedRepository(String name, URI base, String ref, Map<String, String> sha256ByPath) {

    private static final String SOURCE_PREFIX = "# source ";
    private static final String REF_PREFIX = "# ref ";

    public PinnedRepository {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(ref, "ref");
        if (!base.toString().endsWith("/")) {
            throw new IllegalArgumentException("the source URI of " + name + " must end with /: " + base);
        }
        sha256ByPath = Map.copyOf(sha256ByPath);
    }

    /** Loads the pins in {@code <name>.sha256}. */
    public static PinnedRepository load(String name) {
        String resource = name + ".sha256";
        try (InputStream in = PinnedRepository.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no pin file " + resource);
            }
            return parse(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Where {@code path}, relative to the repository root, is downloaded from. */
    public URI uri(String path) {
        return base.resolve(ref + "/" + path);
    }

    static PinnedRepository parse(String name, String text) {
        String source = null;
        String ref = null;
        Map<String, String> pins = new LinkedHashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith(SOURCE_PREFIX)) {
                source = line.substring(SOURCE_PREFIX.length()).strip();
                continue;
            }
            if (line.startsWith(REF_PREFIX)) {
                ref = line.substring(REF_PREFIX.length()).strip();
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            if (parts.length != 2 || !isLowerHex(parts[0], 64)) {
                throw new IllegalArgumentException("pin line must be '<sha256>  <path>': " + line);
            }
            if (pins.put(parts[1], parts[0]) != null) {
                throw new IllegalArgumentException("duplicate pin for " + parts[1]);
            }
        }
        if (source == null) {
            throw new IllegalArgumentException("pin file " + name + " lacks a '# source <uri>' line");
        }
        if (ref == null || !isLowerHex(ref, 40)) {
            throw new IllegalArgumentException("pin file " + name + " lacks a '# ref <commit>' line");
        }
        return new PinnedRepository(name, URI.create(source), ref, pins);
    }

    private static boolean isLowerHex(String text, int length) {
        if (text.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }
}
