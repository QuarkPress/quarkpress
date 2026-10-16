package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WpBlocksFunctionsTest {

    @Test
    void hasBlocksIsWordPressesSubstringTest() { // D18
        assertThat(WpBlocks.hasBlocks("<!-- wp:Bad -->x")).isTrue();
        assertThat(WpBlocks.hasBlocks("<!-- wp:1a /-->")).isTrue();
        assertThat(WpBlocks.hasBlocks("<!-- wp:a/b/c /-->")).isTrue();
        assertThat(WpBlocks.hasBlocks("<!-- wp:a/-->")).isTrue();
        assertThat(WpBlocks.hasBlocks("text <!-- wp: only")).isTrue();
        assertThat(WpBlocks.hasBlocks("<!-- wp:a {\"k\":")).isTrue();
        assertThat(WpBlocks.hasBlocks("<!--\twp:a /-->")).isFalse();
        assertThat(WpBlocks.hasBlocks("<!--\fwp:a\f/-->")).isFalse();
        assertThat(WpBlocks.hasBlocks("<!-- /wp:a -->")).isFalse();
        assertThat(WpBlocks.hasBlocks("<!-- /wp:a /-->")).isFalse();
        assertThat(WpBlocks.hasBlocks("<p>x</p>")).isFalse();
        assertThat(WpBlocks.hasBlocks("")).isFalse();
    }

    @Test
    void hasBlockLooksForTheQualifiedOrTheShortOpener() {
        String doc = "<!-- wp:paragraph {\"a\":1} -->x<!-- /wp:paragraph --><!-- wp:acme/hero /-->"
                + "<!-- wp:core/quote -->q<!-- /wp:core/quote -->";
        assertThat(WpBlocks.hasBlock(doc, "core/paragraph")).isTrue();
        assertThat(WpBlocks.hasBlock(doc, "paragraph")).isTrue();
        assertThat(WpBlocks.hasBlock(doc, "acme/hero")).isTrue();
        assertThat(WpBlocks.hasBlock(doc, "core/quote")).isTrue();
        assertThat(WpBlocks.hasBlock(doc, "quote")).isTrue();
        assertThat(WpBlocks.hasBlock(doc, "a")).isFalse();
        assertThat(WpBlocks.hasBlock(doc, "core/a")).isFalse();
        assertThat(WpBlocks.hasBlock("<!-- wp:a {\"x\":1e400} /-->", "a")).isTrue();
        assertThat(WpBlocks.hasBlock("<!--\twp:a /-->", "a")).isFalse();
        assertThat(WpBlocks.hasBlock("x<!-- /wp:a -->y<!-- wp:b /-->z", "a")).isFalse();
    }

    @Test
    void hasBlockComparesNamesExactly() { // Review Focus 5
        String doc = "<!-- wp:paragraph -->x<!-- /wp:paragraph -->";
        assertThat(WpBlocks.hasBlock(doc, "Paragraph")).isFalse();
        assertThat(WpBlocks.hasBlock(doc, "paragraph ")).isFalse();
        assertThat(WpBlocks.hasBlock(doc, "para")).isFalse();
        assertThat(WpBlocks.hasBlock(doc, "paragraph")).isTrue();
    }

    @Test
    void stripCoreNamespaceDropsOnlyCore() {
        assertThat(WpBlocks.stripCoreNamespace("core/paragraph")).isEqualTo("paragraph");
        assertThat(WpBlocks.stripCoreNamespace("acme/hero")).isEqualTo("acme/hero");
        assertThat(WpBlocks.stripCoreNamespace("paragraph")).isEqualTo("paragraph");
        assertThat(WpBlocks.stripCoreNamespace("core/")).isEmpty();
        assertThat(WpBlocks.stripCoreNamespace("Core/x")).isEqualTo("Core/x");
        assertThatThrownBy(() -> WpBlocks.stripCoreNamespace(null)).isInstanceOf(NullPointerException.class);
    }
}
