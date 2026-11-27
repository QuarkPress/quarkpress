package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class CapitalPTest {

    private static TexturizeStrings withOpeningDoubleQuote(String quote) {
        TexturizeStrings e = TexturizeStrings.english();
        return new TexturizeStrings(
                quote,
                e.closingDoubleQuote(),
                e.apostrophe(),
                e.prime(),
                e.doublePrime(),
                e.openingSingleQuote(),
                e.closingSingleQuote(),
                e.enDash(),
                e.emDash(),
                e.cockney());
    }

    @Test
    void translatedQuote() { // FA49, H12
        TexturizeStrings german = withOpeningDoubleQuote("&#8222;");
        assertThat(Formatting.capitalPDangit("&#8222;Wordpress", CapitalPMode.JUDICIOUS, german))
                .isEqualTo("&#8222;WordPress");
        assertThat(Formatting.capitalPDangit("&#8220;Wordpress", CapitalPMode.JUDICIOUS, german))
                .isEqualTo("&#8220;Wordpress");
        assertThat(Formatting.capitalPDangit("&#8220;Wordpress", CapitalPMode.JUDICIOUS))
                .isEqualTo("&#8220;WordPress");
    }

    @Test
    void judiciousSkipsAStringInitialWord() { // A-G100, A-G101, B-G172
        assertThat(Formatting.capitalPDangit("Wordpress (Wordpress) >Wordpress\tWordpress", CapitalPMode.JUDICIOUS))
                .isEqualTo("Wordpress (WordPress) >WordPress\tWordpress");
        assertThat(Formatting.capitalPDangit("&#8216;Wordpress &#8217;Wordpress", CapitalPMode.JUDICIOUS))
                .isEqualTo("&#8216;WordPress &#8217;Wordpress");
    }

    @Test
    void simpleCorrectsEveryOccurrence() { // A-G103, B-G170
        assertThat(Formatting.capitalPDangit("Wordpress MyWordpressSite wordpress", CapitalPMode.SIMPLE))
                .isEqualTo("WordPress MyWordPressSite wordpress");
    }

    @Test
    void textWithoutTheWordIsReturnedAsIs() {
        String text = "wordpress WORDPRESS";
        assertThat(Formatting.capitalPDangit(text, CapitalPMode.JUDICIOUS)).isSameAs(text);
        assertThat(Formatting.capitalPDangit(text, CapitalPMode.SIMPLE)).isSameAs(text);
    }

    @Test
    void nullIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> Formatting.capitalPDangit(null, CapitalPMode.SIMPLE));
        assertThatNullPointerException().isThrownBy(() -> Formatting.capitalPDangit("x", null));
        assertThatNullPointerException().isThrownBy(() -> Formatting.capitalPDangit("x", CapitalPMode.SIMPLE, null));
    }
}
