package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The parity test's check that a generated case differs from WordPress only by README difference 11. */
class DelimiterNeutralizationTest {

    @Test
    void acceptsOnlyNeutralizedBlockDelimiters() {
        assertThat(WpKsesParityTest.onlyDelimitersNeutralized("x<!-- wp:p >", "x&lt;!-- wp:p >"))
                .isTrue();
        assertThat(WpKsesParityTest.onlyDelimitersNeutralized("<!-- wp:r -->", "&lt;!-- wp:r --&gt;"))
                .isTrue();
        assertThat(WpKsesParityTest.onlyDelimitersNeutralized("a<!--\n/wp:s -->b", "a&lt;!--\n/wp:s --&gt;b"))
                .isTrue();
        assertThat(WpKsesParityTest.onlyDelimitersNeutralized(
                        "<!-- wp:p {\"a\":\"x\"}-->t<!-- wp:q -->",
                        "&lt;!-- wp:p {\"a\":\"x\"}-->t&lt;!-- wp:q --&gt;"))
                .isTrue();
    }

    @Test
    void rejectsEveryOtherChange() {
        String[][] probes = {
            {"a --> b", "a --&gt; b"},
            {"<!-- hi -->", "<!-- hi --&gt;"},
            {"<!-- hi --> <!-- wp:p /-->", "<!-- hi --&gt; &lt;!-- wp:p /-->"},
            {"a --> b <!-- wp:p /-->", "a --&gt; b &lt;!-- wp:p /-->"},
            {"<!-- wp:p --> --> x", "&lt;!-- wp:p --&gt; --&gt; x"},
            {"<!-- x -->", "&lt;!-- x -->"},
            {"a<b>", "a&lt;b>"},
            {"same", "same"},
            {"<!-- wp:p --><script>", "&lt;!-- wp:p --&gt;"},
            {"<!-- wp:p -->", "&lt;!-- wp:p ->"},
            {"a>", "a&gt;"}
        };
        for (String[] p : probes) {
            assertThat(WpKsesParityTest.onlyDelimitersNeutralized(p[0], p[1]))
                    .as(p[0] + " / " + p[1])
                    .isFalse();
        }
    }
}
