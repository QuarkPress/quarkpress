package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BlockDifferencesTest {

    @Test
    void droppedAttributesAreAcceptedOnlyForTheAttributesWrittenForAnOverflow() {
        String dropped = "<!-- wp:p /-->";
        assertThat(BlockDifferences.check(dropped, "<!-- wp:p {\"n\":null} /-->", true, true))
                .isEqualTo(new BlockDifferences.Verdict(true, true, false));
        assertThat(BlockDifferences.check(dropped, "<!-- wp:p {\"n\":1" + "0".repeat(400) + "} /-->", true, true)
                        .accepted())
                .isTrue();
        assertThat(BlockDifferences.check(dropped, "<!-- wp:p {\"n\":1} /-->", true, true))
                .isEqualTo(BlockDifferences.Verdict.REJECTED);
        assertThat(BlockDifferences.check(dropped, "<!-- wp:p {\"n\":null} /-->", true, false))
                .isEqualTo(BlockDifferences.Verdict.REJECTED);
    }

    @Test
    void aNeutralizedDelimiterMayDifferOnlyAsKsesChangesAComment() {
        assertThat(BlockDifferences.check(
                        "<!-- wp:p {\"a\":\"b\"c-d\"} -->", "&lt;!-- wp:p {\"a\":\"b\\\"c--d\"} --&gt;", true, false))
                .isEqualTo(new BlockDifferences.Verdict(true, false, true));
        assertThat(BlockDifferences.check(
                        "<!-- wp:p {\"a\":\"bc\"} -->", "&lt;!-- wp:p {\"a\":\"b\\c\"} --&gt;", true, false))
                .isEqualTo(BlockDifferences.Verdict.REJECTED);
    }

    @Test
    void attributesCountAsRewrittenOnlyWhenTheirValuesDiffer() {
        assertThat(BlockDifferences.check("<!-- wp:p {\"a\":1} -->", "<!-- wp:p { \"a\": 1 } -->", true, false))
                .isEqualTo(new BlockDifferences.Verdict(true, false, false));
        assertThat(BlockDifferences.check("<!-- wp:p {\"a\":[]} -->", "<!-- wp:p {\"a\":{}} -->", true, false))
                .isEqualTo(new BlockDifferences.Verdict(true, true, false));
    }
}
