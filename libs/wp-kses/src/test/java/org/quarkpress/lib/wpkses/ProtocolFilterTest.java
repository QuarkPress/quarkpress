package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolFilterTest {

    private static final List<String> DEFAULT = WordPressTables.INSTANCE.protocols();

    @Test
    void matchesWordPress() {
        Examples.assertFile("bad-protocol.json");
    }

    @Test
    void referencesBeyondPhpIntegersAreTakenModulo256() {
        // README #8 (ruling M8): WordPress throws a TypeError here; QuarkPress keeps going with the number modulo 256.
        assertThat(ProtocolFilter.filter("&#99999999999999999999;:x", DEFAULT)).isEqualTo("x");
        assertThat(ProtocolFilter.filter("&#x99999999999999999999;:x", DEFAULT)).isEqualTo("x");
    }

    @Test
    void separatorsAreFoundAnywhereAndInAnyCase() {
        assertThat(ProtocolFilter.firstSeparator("ab&COLON;c")).containsExactly(2, 9);
        assertThat(ProtocolFilter.firstSeparator("ab&#0058;c")).containsExactly(2, 9);
        assertThat(ProtocolFilter.firstSeparator("ab&#X003A;c")).containsExactly(2, 10);
        assertThat(ProtocolFilter.firstSeparator("ab&#5;c")).isNull();
    }

    @Test
    void missingSemicolonsAreAddedOnlyBeforeNonDigits() {
        assertThat(ProtocolFilter.addMissingColonSemicolons("a&#58b")).isEqualTo("a&#58;b");
        assertThat(ProtocolFilter.addMissingColonSemicolons("a&#580")).isEqualTo("a&#580");
        assertThat(ProtocolFilter.addMissingColonSemicolons("a&#x3ab")).isEqualTo("a&#x3ab");
        assertThat(ProtocolFilter.addMissingColonSemicolons("a&#x3Ag")).isEqualTo("a&#x3A;g");
        assertThat(ProtocolFilter.addMissingColonSemicolons("a&#58")).isEqualTo("a&#58;");
    }

    @Test
    void explicitListsAreComparedWithoutCase() {
        assertThat(ProtocolFilter.filter("Mailto:A@B", List.of("MAILTO"))).isEqualTo("mailto:A@B");
        assertThat(ProtocolFilter.filter("http://x", List.of())).isEqualTo("//x");
    }

    @Test
    void longValuesStayLinear() {
        String colons = "a:".repeat(500_000);
        String references = "&#58".repeat(250_000);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // Every pass removes one segment and the sixth pass still changes the value, so both end empty.
            assertThat(ProtocolFilter.filter(colons, DEFAULT)).isEmpty();
            assertThat(ProtocolFilter.filter(references, DEFAULT)).isEmpty();
        });
    }
}
