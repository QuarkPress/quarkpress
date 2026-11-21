package org.quarkpress.it.keycloak;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The hashes WordPress 7.1.3 made, read from the quarkpress-keycloak checkout's fixture file ({@code
 * wp-password/src/test/resources/wordpress-7.1.3-hashes.txt}, its header names how they were made): one row per id
 * with the password and the stored hash. Only the columns these tests need are read.
 */
final class WordPressHashes {

    /** One row: the password (decoded from its hex column) and the hash WordPress stored for it. */
    record Row(String id, String password, String hash) {

        @Override
        public String toString() {
            return id;
        }
    }

    private static Map<String, Row> rows;

    private WordPressHashes() {}

    /** The row with this id. */
    static synchronized Row row(String id) {
        if (rows == null) {
            rows = read();
        }
        Row row = rows.get(id);
        if (row == null) {
            throw new IllegalArgumentException("no fixture row " + id);
        }
        return row;
    }

    private static Map<String, Row> read() {
        Map<String, Row> read = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(ProviderJarCondition.file(ProviderJarCondition.HASHES), UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split(" ");
                if (f.length != 7) {
                    throw new IllegalStateException("a fixture row has " + f.length + " columns, not 7: " + f[0]);
                }
                String password =
                        f[2].equals("<empty>") ? "" : new String(HexFormat.of().parseHex(f[2]), UTF_8);
                read.put(f[0], new Row(f[0], password, f[3]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (read.isEmpty()) {
            throw new IllegalStateException("the fixture file has no rows");
        }
        return read;
    }
}
