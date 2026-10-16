package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ParseOptionsTest {

    @Test
    void hasNoPublicConstructorSoOptionsCanGrowCompatibly() {
        assertThat(Arrays.stream(ParseOptions.class.getDeclaredConstructors())
                        .anyMatch(c -> Modifier.isPublic(c.getModifiers())))
                .isFalse();
        assertThat(ParseOptions.class.isRecord()).isFalse();
    }

    @Test
    void withersReturnModifiedCopies() {
        ParseOptions o = ParseOptions.DEFAULTS
                .withCharset(StandardCharsets.ISO_8859_1)
                .withMaxDepth(10)
                .withMaxElements(5)
                .withMaxStringBytes(7)
                .withRepairLengths(true);
        assertThat(o.charset()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(o.maxDepth()).isEqualTo(10);
        assertThat(o.maxElements()).isEqualTo(5);
        assertThat(o.maxStringBytes()).isEqualTo(7);
        assertThat(o.repairLengths()).isTrue();
        assertThat(ParseOptions.DEFAULTS.repairLengths()).isFalse();
        assertThat(ParseOptions.DEFAULTS.maxDepth()).isEqualTo(256);
    }

    @Test
    void rejectsNonsenseValues() {
        assertThatThrownBy(() -> ParseOptions.DEFAULTS.withMaxDepth(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParseOptions.DEFAULTS.withMaxElements(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParseOptions.DEFAULTS.withMaxStringBytes(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParseOptions.DEFAULTS.withCharset(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void phpCompatiblePresetEnablesThePhpLeniencies() {
        assertThat(ParseOptions.DEFAULTS.allowTrailingData()).isFalse();
        assertThat(ParseOptions.DEFAULTS.clampIntegers()).isFalse();
        assertThat(ParseOptions.DEFAULTS.legacyEscapedStrings()).isFalse();
        ParseOptions php = ParseOptions.PHP_COMPATIBLE;
        assertThat(php.allowTrailingData()).isTrue();
        assertThat(php.clampIntegers()).isTrue();
        assertThat(php.legacyEscapedStrings()).isTrue();
        assertThat(php.repairLengths()).isFalse();
        assertThat(php.maxDepth()).isEqualTo(256);
        assertThat(ParseOptions.DEFAULTS.withClampIntegers(true).clampIntegers())
                .isTrue();
        assertThat(ParseOptions.DEFAULTS.withLegacyEscapedStrings(true).legacyEscapedStrings())
                .isTrue();
        assertThat(ParseOptions.DEFAULTS
                        .withAllowTrailingData(true)
                        .withMaxDepth(10)
                        .allowTrailingData())
                .isTrue();
    }
}
