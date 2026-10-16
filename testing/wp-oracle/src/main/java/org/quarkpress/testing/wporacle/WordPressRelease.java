package org.quarkpress.testing.wporacle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A pinned WordPress release: the {@code wordpress-develop} tag (equal to the version), the commit it points
 * to, and the SHA-256 of every file the oracle may download. Pins come from the classpath resource {@code
 * wp-<version>.sha256} next to this class; bumping WordPress means adding a pin file and changing the {@code
 * wp.version} property of the {@code wp-parity} profile.
 */
public record WordPressRelease(String version, String commit, Map<String, String> sha256ByPath) {

    private static final String COMMIT_PREFIX = "# commit ";

    public WordPressRelease {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(commit, "commit");
        sha256ByPath = Map.copyOf(sha256ByPath);
    }

    /** The git tag to download from. */
    public String tag() {
        return version;
    }

    /** Loads the pins for {@code version}. */
    public static WordPressRelease load(String version) {
        String resource = "wp-" + version + ".sha256";
        try (InputStream in = WordPressRelease.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no pin file " + resource + " for WordPress " + version);
            }
            return parse(version, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The release named by the {@code wp.version} system property, which the {@code wp-parity} profile sets. */
    public static WordPressRelease fromSystemProperty() {
        String version = System.getProperty("wp.version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("wp.version is not set; run with -Pwp-parity");
        }
        return load(version);
    }

    static WordPressRelease parse(String version, String text) {
        String commit = null;
        Map<String, String> pins = new LinkedHashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith(COMMIT_PREFIX)) {
                commit = line.substring(COMMIT_PREFIX.length()).strip();
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            if (parts.length != 2 || !parts[0].matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("pin line must be '<sha256>  <path>': " + line);
            }
            if (pins.put(parts[1], parts[0]) != null) {
                throw new IllegalArgumentException("duplicate pin for " + parts[1]);
            }
        }
        if (commit == null || !commit.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("pin file for WordPress " + version + " lacks a '# commit <sha>' line");
        }
        return new WordPressRelease(version, commit, pins);
    }
}
