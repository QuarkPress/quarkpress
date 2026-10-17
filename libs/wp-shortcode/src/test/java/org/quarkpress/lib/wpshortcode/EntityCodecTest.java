package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EntityCodecTest {

    @Test
    void normalizesOnlyTheExactFiveCharForms() {
        assertThat(EntityCodec.normalize("&#91;x&#93; &#091; &#x5B; &#9; &#91 &#913;"))
                .isEqualTo("&#091;x&#093; &#091; &#x5B; &#9; &#91 &#913;");
        String plain = "no entities";
        assertThat(EntityCodec.normalize(plain)).isSameAs(plain);
    }

    @Test
    void unescapesOnlyTheExactFiveCharForms() {
        assertThat(EntityCodec.unescape("&#91;x&#93; &#091; &#&#91;")).isEqualTo("[x] &#091; &#[");
    }

    @Test
    void encodesBrackets() {
        assertThat(EntityCodec.encodeBrackets("<p title=\"[x]\">")).isEqualTo("<p title=\"&#91;x&#93;\">");
    }
}
