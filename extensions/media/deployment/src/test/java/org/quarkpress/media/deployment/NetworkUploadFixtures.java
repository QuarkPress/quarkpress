package org.quarkpress.media.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.quarkpress.persistence.NetworkOptions;

/**
 * For the network upload-limit tests: network options saved before a test and put back after it (the database is
 * shared by every test application), and the text files they upload.
 */
public final class NetworkUploadFixtures {

    /** The network options the upload limits and types read. */
    public static final List<String> OPTIONS =
            List.of("upload_space_check_disabled", "fileupload_maxk", "blog_upload_space", "upload_filetypes");

    private final NetworkOptions network;
    private final Map<String, Optional<JsonNode>> saved = new LinkedHashMap<>();

    private NetworkUploadFixtures(NetworkOptions network) {
        this.network = network;
        for (String name : OPTIONS) {
            saved.put(name, network.find(name));
        }
    }

    /** Saves the current values of {@link #OPTIONS}. */
    public static NetworkUploadFixtures save(NetworkOptions network) {
        return new NetworkUploadFixtures(network);
    }

    /** The value {@code name} had when saved. */
    public Optional<JsonNode> saved(String name) {
        return saved.get(name);
    }

    /** Puts every saved option back, deleting those that did not exist. */
    public void restore() {
        saved.forEach((name, value) -> {
            if (value.isPresent()) {
                network.set(name, value.get());
            } else {
                network.delete(name);
            }
        });
    }

    /** A plain-text file of {@code size} bytes ({@code a}s, a line feed last) under {@code target/<folder>}. */
    public static Path text(String folder, long size) {
        byte[] bytes = new byte[Math.toIntExact(size)];
        Arrays.fill(bytes, (byte) 'a');
        if (size > 0) {
            bytes[bytes.length - 1] = '\n';
        }
        try {
            Path dir = Files.createDirectories(Path.of("target", folder));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
