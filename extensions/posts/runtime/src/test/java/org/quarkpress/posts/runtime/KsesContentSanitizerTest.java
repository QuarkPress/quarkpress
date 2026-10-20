package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quarkpress.posts.SaveFilters;

class KsesContentSanitizerTest {

    static final SaveFilters FILTERED = new SaveFilters(true, true);

    final KsesContentSanitizer sanitizer = new KsesContentSanitizer();

    @Test
    void titlesAreTrimmedThenFiltered() {
        assertThat(sanitizer.title("  <em>Hello</em> <img src=x>  ", FILTERED)).isEqualTo("<em>Hello</em> ");
        assertThat(sanitizer.title(" <img src=x> Hello", FILTERED)).isEqualTo(" Hello");
        assertThat(sanitizer.title("<script>alert(1)</script>Hi", FILTERED)).isEqualTo("alert(1)Hi");
        assertThat(sanitizer.title("Tom & Jerry", FILTERED)).isEqualTo("Tom &amp; Jerry");
        assertThat(sanitizer.title("A &amp; B < C", FILTERED)).isEqualTo("A &amp; B &lt; C");
    }

    @Test
    void unfilteredTitlesAreOnlyTrimmed() {
        assertThat(sanitizer.title("  Tom & Jerry  ", SaveFilters.NONE)).isEqualTo("Tom & Jerry");
        assertThat(sanitizer.title("<script>x</script>", SaveFilters.NONE)).isEqualTo("<script>x</script>");
    }

    @Test
    void aNulAtEitherEndOfATitleIsNotTrimmed() {
        assertThat(sanitizer.title("Title \0", SaveFilters.NONE)).isEqualTo("Title \0");
        assertThat(sanitizer.title("\0 Title", SaveFilters.NONE)).isEqualTo("\0 Title");
        assertThat(sanitizer.title("\t\n Title \013\r ", SaveFilters.NONE)).isEqualTo("Title");
        assertThat(sanitizer.title("\t\n Title \013\0", FILTERED)).isEqualTo("Title ");
    }

    @Test
    void contentIsFilteredWithThePostTagSet() {
        assertThat(sanitizer.content("<p onclick=\"x()\">Hi</p>", FILTERED)).isEqualTo("<p>Hi</p>");
        assertThat(sanitizer.content("<iframe src=\"x\"></iframe>ok", FILTERED)).isEqualTo("ok");
        assertThat(sanitizer.content("<div class=\"a\" style=\"color:red\">x</div>", FILTERED))
                .isEqualTo("<div class=\"a\" style=\"color:red\">x</div>");
    }

    @Test
    void invalidEntitiesAreConvertedForEveryone() {
        assertThat(sanitizer.content("a &#150; b", FILTERED)).isEqualTo("a &#8211; b");
        assertThat(sanitizer.content("a &#150; b", SaveFilters.NONE)).isEqualTo("a &#8211; b");
        assertThat(sanitizer.excerpt("a &#150; b", SaveFilters.NONE)).isEqualTo("a &#8211; b");
    }

    @Test
    void blockCustomCssIsStrippedOnlyForCallersWithoutEditCss() {
        String content = "<!-- wp:paragraph {\"style\":{\"css\":\"color:red\"}} --><p>x</p><!-- /wp:paragraph -->";
        assertThat(sanitizer.content(content, FILTERED))
                .isEqualTo("<!-- wp:paragraph --><p>x</p><!-- /wp:paragraph -->");
        assertThat(sanitizer.content(content, SaveFilters.NONE)).isEqualTo(content);
        assertThat(sanitizer.content(content, new SaveFilters(false, true)))
                .isEqualTo("<!-- wp:paragraph --><p>x</p><!-- /wp:paragraph -->");
    }

    @Test
    void excerptsAreFilteredWithThePostTagSet() {
        assertThat(sanitizer.excerpt("<p onclick=\"x()\">Hi</p>", FILTERED)).isEqualTo("<p>Hi</p>");
        assertThat(sanitizer.excerpt("<p onclick=\"x()\">Hi</p>", SaveFilters.NONE))
                .isEqualTo("<p onclick=\"x()\">Hi</p>");
    }
}
