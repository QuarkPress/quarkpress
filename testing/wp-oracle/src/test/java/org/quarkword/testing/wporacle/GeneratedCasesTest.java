package org.quarkword.testing.wporacle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkword.testing.gen.Gen;
import org.quarkword.testing.gen.Gens;

class GeneratedCasesTest {

    @Test
    void theSameSeedGivesTheSameSamples() {
        Gen<String> strings = Gens.strings("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", 1, 8);
        List<String> first = GeneratedCases.sample(strings, "42", 50);
        assertThat(first).hasSize(50);
        assertThat(GeneratedCases.sample(strings, "42", 50)).isEqualTo(first);
        assertThat(GeneratedCases.sample(strings, "43", 50)).isNotEqualTo(first);
    }
}
