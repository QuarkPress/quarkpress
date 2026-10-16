package org.quarkpress.testing.wporacle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import org.junit.jupiter.api.Test;

class GeneratedCasesTest {

    @Test
    void theSameSeedGivesTheSameSamples() {
        Arbitrary<String> strings = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
        List<String> first = GeneratedCases.sample(strings, "42", 50);
        assertThat(first).hasSize(50);
        assertThat(GeneratedCases.sample(strings, "42", 50)).isEqualTo(first);
        assertThat(GeneratedCases.sample(strings, "43", 50)).isNotEqualTo(first);
    }
}
