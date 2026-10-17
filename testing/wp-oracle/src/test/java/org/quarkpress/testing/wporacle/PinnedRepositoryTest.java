package org.quarkpress.testing.wporacle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class PinnedRepositoryTest {

    private static final String HEADER = "# source https://example.test/repo/\n# ref " + "a".repeat(40) + "\n";

    @Test
    void loadsTheImporterPins() {
        PinnedRepository importer = PinnedRepository.load("wordpress-importer-0.9.6");
        assertThat(importer.name()).isEqualTo("wordpress-importer-0.9.6");
        assertThat(importer.ref()).isEqualTo("3ca8839a2af470a81395bdfe0fc76d5c7beac071");
        assertThat(importer.base())
                .isEqualTo(URI.create("https://raw.githubusercontent.com/WordPress/wordpress-importer/"));
        assertThat(importer.sha256ByPath())
                .containsKeys(
                        "src/parsers/class-wxr-parser.php",
                        "src/parsers/class-wxr-parser-regex.php",
                        "src/parsers/class-wxr-parser-simplexml.php",
                        "src/parsers/class-wxr-parser-xml.php",
                        "src/parsers/class-wxr-parser-xml-processor.php",
                        "src/class-wp-import.php",
                        "src/php-toolkit/load.php")
                .containsEntry(
                        "src/parsers/class-wxr-parser.php",
                        "ce0b83e47999df57e25281fdbf03b8de74d1f5f917e81a2624086d914ff4e8a1")
                .containsEntry(
                        "src/class-wp-import.php", "92bac7d2dc882b5a8f81573bb5489ebb8d7eb368bfe25a7d949c234933d4ef36");
        assertThat(importer.sha256ByPath().values()).allMatch(hash -> hash.matches("[0-9a-f]{64}"));
        assertThat(importer.uri("src/parsers/class-wxr-parser.php"))
                .isEqualTo(URI.create("https://raw.githubusercontent.com/WordPress/wordpress-importer/"
                        + "3ca8839a2af470a81395bdfe0fc76d5c7beac071/src/parsers/class-wxr-parser.php"));
    }

    @Test
    void unknownNameFails() {
        assertThatThrownBy(() -> PinnedRepository.load("nope-1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope-1.0.sha256");
    }

    @Test
    void malformedPinFilesAreRejected() {
        String pin = "b".repeat(64) + "  src/a.php\n";
        assertThatThrownBy(() -> PinnedRepository.parse("x", "# ref " + "a".repeat(40) + "\n" + pin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
        assertThatThrownBy(() -> PinnedRepository.parse("x", "# source https://example.test/repo/\n" + pin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ref");
        assertThatThrownBy(() -> PinnedRepository.parse(
                        "x", "# source https://example.test/repo\n# ref " + "a".repeat(40) + "\n" + pin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/");
        assertThatThrownBy(() -> PinnedRepository.parse("x", HEADER + "nothex  src/a.php\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PinnedRepository.parse("x", HEADER + pin + pin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }
}
