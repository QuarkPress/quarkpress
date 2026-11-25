package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The number of content pages WordPress 7.1.3's {@code generate_postdata} splits a post into ({@code numpages}),
 * recorded with PHP (main-query.php / main-query.out in the slice folder).
 */
class ContentPagesTest {

    @Test
    void theReferencePostHasThreePages() {
        assertThat(ContentPages.count("Intro<!--more-->Rest of one\n<!--nextpage-->\nTwo<!--nextpage-->Three"))
                .isEqualTo(3);
    }

    @Test
    void aTrailingMarkerLeavesAnEmptyLastPage() {
        assertThat(ContentPages.count("A<!--nextpage-->")).isEqualTo(2);
    }

    @Test
    void aLeadingMarkerIsDropped() {
        assertThat(ContentPages.count("<!--nextpage-->A")).isEqualTo(1);
    }

    @Test
    void theBlockDelimitersAroundAMarkerAreRemoved() {
        assertThat(ContentPages.count("A\n<!-- wp:nextpage --><!--nextpage--><!-- /wp:nextpage -->\nB"))
                .isEqualTo(2);
        assertThat(ContentPages.count("<!-- wp:nextpage -->\n<!--nextpage-->\n<!-- /wp:nextpage -->A"))
                .isEqualTo(1);
    }

    @Test
    void contentWithoutAMarkerIsOnePage() {
        assertThat(ContentPages.count("Plain")).isEqualTo(1);
        assertThat(ContentPages.count("")).isEqualTo(1);
        assertThat(ContentPages.count("A<!--nextpage--><!--nextpage-->B")).isEqualTo(3);
    }
}
