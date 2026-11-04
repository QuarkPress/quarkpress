package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The preview page's reading of the session cookies a request carries. */
class PreviewCookiesTest {

    @Test
    void everySessionCookieInTheOrderSent() {
        assertThat(PreviewPage.sessionCookies(List.of("quarkpress_preview=a; other=x; quarkpress_preview=b")))
                .containsExactly("a", "b");
        assertThat(PreviewPage.sessionCookies(List.of("other=x", " quarkpress_preview = \"c\" ;quarkpress_preview=d")))
                .containsExactly("c", "d");
    }

    @Test
    void otherNamesEmptyValuesAndBrokenPairsAreSkipped() {
        assertThat(PreviewPage.sessionCookies(List.of(
                        "quarkpress_preview_old=a; xquarkpress_preview=b; quarkpress_preview=; quarkpress_preview;"
                                + " =quarkpress_preview; ;;")))
                .isEmpty();
        assertThat(PreviewPage.sessionCookies(List.of())).isEmpty();
        assertThat(PreviewPage.sessionCookies(List.of(""))).isEmpty();
    }

    @Test
    void atMostTenAreRead() {
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            pairs.add("quarkpress_preview=v" + i);
        }
        assertThat(PreviewPage.sessionCookies(List.of(String.join("; ", pairs), "quarkpress_preview=late")))
                .hasSize(10)
                .startsWith("v0")
                .endsWith("v9");
    }
}
