package org.quarkword.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The preview page's reading of the session cookies a request carries. */
class PreviewCookiesTest {

    @Test
    void everySessionCookieInTheOrderSent() {
        assertThat(PreviewPage.sessionCookies(List.of("quarkword_preview=a; other=x; quarkword_preview=b")))
                .containsExactly("a", "b");
        assertThat(PreviewPage.sessionCookies(List.of("other=x", " quarkword_preview = \"c\" ;quarkword_preview=d")))
                .containsExactly("c", "d");
    }

    @Test
    void otherNamesEmptyValuesAndBrokenPairsAreSkipped() {
        assertThat(PreviewPage.sessionCookies(
                        List.of("quarkword_preview_old=a; xquarkword_preview=b; quarkword_preview=; quarkword_preview;"
                                + " =quarkword_preview; ;;")))
                .isEmpty();
        assertThat(PreviewPage.sessionCookies(List.of())).isEmpty();
        assertThat(PreviewPage.sessionCookies(List.of(""))).isEmpty();
    }

    @Test
    void atMostTenAreRead() {
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            pairs.add("quarkword_preview=v" + i);
        }
        assertThat(PreviewPage.sessionCookies(List.of(String.join("; ", pairs), "quarkword_preview=late")))
                .hasSize(10)
                .startsWith("v0")
                .endsWith("v9");
    }
}
