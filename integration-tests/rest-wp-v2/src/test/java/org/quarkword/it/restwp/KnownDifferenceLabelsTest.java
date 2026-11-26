package org.quarkword.it.restwp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each parity known-differences file and the "Differences from WordPress" table of its extension's README name the
 * same differences: the REST API's file ({@code wp-rest-parity-known-differences.txt}) with the REST extension's
 * README, the front-end scenarios' file ({@code wp-front-parity-known-differences.txt}) with the front extension's.
 * Every label an entry uses is a row of the table, so an entry never cites a difference the README does not explain,
 * and every row is used by at least one entry (a scenario that shows it, or a {@code none} line saying why none can),
 * so the file is complete. Reads the files only: no application, no database.
 */
class KnownDifferenceLabelsTest {

    /** The REST file {@code RestParityDbTest} reads. */
    static final Path KNOWN = Path.of("src", "test", "resources", "wp-rest-parity-known-differences.txt");

    static final Path README = Path.of("..", "..", "extensions", "rest-wp-v2", "README.md");

    /** The front-end file {@code RestParityDbTest} reads. */
    static final Path KNOWN_FRONT = Path.of("src", "test", "resources", "wp-front-parity-known-differences.txt");

    static final Path README_FRONT = Path.of("..", "..", "extensions", "front", "README.md");

    static final String TABLE = "## Differences from WordPress";

    static Stream<Arguments> pairs() {
        return Stream.of(Arguments.of(KNOWN, README), Arguments.of(KNOWN_FRONT, README_FRONT));
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void theKnownDifferencesAndTheReadmeNameTheSameDifferences(Path known, Path readme) throws IOException {
        List<String> used = usedLabels(Files.readAllLines(known, StandardCharsets.UTF_8));
        List<String> rows = readmeLabels(Files.readAllLines(readme, StandardCharsets.UTF_8));

        assertThat(used).as("labels used by %s", known).isNotEmpty().allMatch(this::isLabel);
        assertThat(rows).as("rows of %s in %s", TABLE, readme).doesNotHaveDuplicates();
        assertThat(rows).as("rows of %s in %s", TABLE, readme).containsAll(used);
        assertThat(used).as("labels used by %s", known).containsAll(rows);
    }

    /** The third field of each entry (scenario, aspect, label, reason), in order of first use. */
    static List<String> usedLabels(List<String> lines) {
        Set<String> labels = new LinkedHashSet<>();
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            List<String> fields = fields(text);
            assertThat(fields).as("entry %s", text).hasSizeGreaterThanOrEqualTo(4);
            labels.add(fields.get(2));
        }
        return new ArrayList<>(labels);
    }

    /** The first cell of each row of the differences table, from its heading to the next heading. */
    static List<String> readmeLabels(List<String> lines) {
        List<String> labels = new ArrayList<>();
        boolean inTable = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                inTable = line.strip().equals(TABLE);
                continue;
            }
            if (!inTable || !line.startsWith("| ")) {
                continue;
            }
            int end = line.indexOf(" |", 2);
            if (end > 2) {
                labels.add(line.substring(2, end).strip());
            }
        }
        labels.remove("Label");
        return labels;
    }

    /** {@code D} and digits. */
    boolean isLabel(String label) {
        if (label.length() < 2 || label.charAt(0) != 'D') {
            return false;
        }
        for (int i = 1; i < label.length(); i++) {
            if (label.charAt(i) < '0' || label.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static List<String> fields(String text) {
        List<String> fields = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i > start) {
                fields.add(text.substring(start, i));
            }
        }
        return fields;
    }
}
