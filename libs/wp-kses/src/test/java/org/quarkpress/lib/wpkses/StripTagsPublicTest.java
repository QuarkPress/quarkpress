package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StripTagsPublicTest {

    @Test
    void stripsTagsButKeepsScriptContentAsPhpDoes() {
        assertThat(Kses.stripTags("<b>Bold</b> title")).isEqualTo("Bold title");
        assertThat(Kses.stripTags("<script>x()</script>y")).isEqualTo("x()y");
        assertThat(Kses.stripTags("a < b")).isEqualTo("a < b");
        assertThat(Kses.stripTags("<!-- c -->name")).isEqualTo("name");
    }

    @Test
    void refusesNull() {
        assertThatThrownBy(() -> Kses.stripTags(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("text");
    }
}
