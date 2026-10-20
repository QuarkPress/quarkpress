package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomCssTest {

    @Test
    void contentWithoutBlocksIsUnchanged() {
        String html = "<p style=\"color:red\">x</p>";
        assertThat(WpBlocks.stripCustomCss(html)).isSameAs(html);
    }

    @Test
    void theCssKeyAndAnEmptiedStyleAndEmptiedAttributesAreRemoved() {
        assertThat(WpBlocks.stripCustomCss(
                        "<!-- wp:paragraph {\"style\":{\"css\":\"color:red\"}} --><p>x</p><!-- /wp:paragraph -->"))
                .isEqualTo("<!-- wp:paragraph --><p>x</p><!-- /wp:paragraph -->");
        assertThat(WpBlocks.stripCustomCss("<!-- wp:spacer {\"style\":{\"css\":\"x\"}} /-->"))
                .isEqualTo("<!-- wp:spacer /-->");
    }

    @Test
    void otherAttributesStayAndAreWrittenAsWordPressWritesThem() {
        assertThat(WpBlocks.stripCustomCss(
                        "<!-- wp:group {\"style\":{\"css\":\"a\",\"color\":{\"text\":\"red\"}},\"x\":1} /-->"))
                .isEqualTo("<!-- wp:group {\"style\":{\"color\":{\"text\":\"red\"}},\"x\":1} /-->");
        assertThat(WpBlocks.stripCustomCss("<!-- wp:group {\"style\":{\"css\":\"a\"},\"label\":\"a--b<c>\"} /-->"))
                .isEqualTo("<!-- wp:group {\"label\":\"a\\u002d\\u002db\\u003cc\\u003e\"} /-->");
        assertThat(WpBlocks.stripCustomCss("<!-- wp:group {\"style\":{\"css\":\"a\",\"typography\":{}}} /-->"))
                .isEqualTo("<!-- wp:group {\"style\":{\"typography\":[]}} /-->");
    }

    @Test
    void integersBeyondSixtyFourBitsAreWrittenAsPhpWritesTheFloatItReads() {
        assertThat(
                        WpBlocks.stripCustomCss(
                                "<!-- wp:group {\"style\":{\"css\":\"a\"},\"big\":12345678901234567890,\"neg\":-9223372036854775808} /-->"))
                .isEqualTo("<!-- wp:group {\"big\":1.2345678901234567e+19,\"neg\":-9223372036854775808} /-->");
    }

    @Test
    void attributesThatCannotBeEncodedAreDroppedWithoutTheCharacterAfterThem() {
        assertThat(WpBlocks.stripCustomCss("<!-- wp:group {\"style\":{\"css\":\"a\"},\"n\":1e400} /-->"))
                .isEqualTo("<!-- wp:group  /-->");
    }

    @Test
    void anIntegerTooLargeForAFloatDropsTheAttributesToo() {
        String huge = "1" + "0".repeat(400);
        assertThat(WpBlocks.stripCustomCss("<!-- wp:group {\"style\":{\"css\":\"a\"},\"n\":" + huge + ",\"m\":1} /-->"))
                .isEqualTo("<!-- wp:group  /-->");
        assertThat(WpBlocks.stripCustomCss("<!-- wp:group {\"style\":{\"css\":\"a\"},\"n\":-" + huge + "} /-->"))
                .isEqualTo("<!-- wp:group  /-->");
    }

    @Test
    void everyBlockIsStrippedNestedOnesToo() {
        String content = "<!-- wp:group {\"style\":{\"css\":\"a\"}} --><div><!-- wp:paragraph"
                + " {\"style\":{\"css\":\"b\"},\"align\":\"wide\"} --><p>x</p><!-- /wp:paragraph --></div><!-- /wp:group -->";
        assertThat(WpBlocks.stripCustomCss(content))
                .isEqualTo("<!-- wp:group --><div><!-- wp:paragraph {\"align\":\"wide\"} --><p>x</p>"
                        + "<!-- /wp:paragraph --></div><!-- /wp:group -->");
    }

    @Test
    void attributesWithoutCssOrThatAreNotJsonAreLeftAlone() {
        String nullCss = "<!-- wp:paragraph {\"style\":{\"css\":null}} --><p>x</p><!-- /wp:paragraph -->";
        assertThat(WpBlocks.stripCustomCss(nullCss)).isEqualTo(nullCss);
        String broken = "<!-- wp:paragraph {\"style\":{\"css\":} } --><p>x</p><!-- /wp:paragraph -->";
        assertThat(WpBlocks.stripCustomCss(broken)).isEqualTo(broken);
        String listStyle = "<!-- wp:paragraph {\"style\":[\"css\"]} /-->";
        assertThat(WpBlocks.stripCustomCss(listStyle)).isEqualTo(listStyle);
    }
}
