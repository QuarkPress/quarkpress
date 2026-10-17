package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EntitiesTest {

    private static final Shortcodes SHORTCODES =
            Shortcodes.builder(Registries.html()).build();

    private static void assertApply(String content, String expected) {
        assertThat(SHORTCODES.apply(content)).as(content).isEqualTo(expected);
    }

    @Test
    void literalBracketEntitiesAreNormalizedOnlyWhenSomethingIsActive() { // R13.1, R13.3, R2.1
        assertApply("[foo] &#91;x&#93;", "{foo:[]:\"\"} &#091;x&#093;");
        assertApply("[foo] &#091;x&#093;", "{foo:[]:\"\"} &#091;x&#093;");
        assertApply("[foo] &#x5B;x&#x5D;", "{foo:[]:\"\"} &#x5B;x&#x5D;");
        assertApply("&#91;x&#93; and [nope]", "&#91;x&#93; and [nope]");
        assertApply("<a title=\"&#91;cls&#93;\">[cls]</a>", "<a title=\"&#091;cls&#093;\">red</a>");
        assertApply("[foo]&#91;foo&#93;[/foo]", "{foo:[]:\"&#091;foo&#093;\"}");
        assertApply("&#91;foo&#93;", "&#91;foo&#93;");
    }
}
