package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class WordPressKeysTest {

    @Test
    void lowerCasesAndKeepsOnlyKeyCharacters() {
        assertThat(WordPressKeys.sanitize("Publish")).isEqualTo("publish");
        assertThat(WordPressKeys.sanitize("Page ")).isEqualTo("page");
        assertThat(WordPressKeys.sanitize("Needs Review")).isEqualTo("needsreview");
        assertThat(WordPressKeys.sanitize("a.b-c_d!")).isEqualTo("ab-c_d");
        assertThat(WordPressKeys.sanitize("caf" + (char) 0xE9)).isEqualTo("caf");
        assertThat(WordPressKeys.sanitize("")).isEmpty();
        assertThat(WordPressKeys.sanitize("AZaz09@[`{/:")).isEqualTo("azaz09");
    }

    @Test
    void theResultHoldsOnlyKeyCharactersAndIsStable() {
        Properties.check(
                "sanitize_key keeps a-z, 0-9, _ and - and is idempotent",
                2_000,
                Gens.strings("aZ09_- .!" + (char) 0xE9 + (char) 0x418, 0, 30),
                key -> {
                    String sanitized = WordPressKeys.sanitize(key);
                    assertThat(sanitized.chars())
                            .allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-');
                    assertThat(WordPressKeys.sanitize(sanitized)).isEqualTo(sanitized);
                });
    }
}
