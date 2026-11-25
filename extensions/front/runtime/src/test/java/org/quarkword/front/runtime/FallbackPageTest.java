package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The fallback page's HTML: texts escaped as WordPress's {@code esc_html} escapes them, the content as filtered. */
class FallbackPageTest {

    private static final ZonedDateTime DATE = ZonedDateTime.of(2025, 11, 20, 10, 15, 30, 0, ZoneOffset.UTC);

    private static FallbackPage.Singular post(String siteName, String title, Optional<String> content) {
        return new FallbackPage.Singular(siteName, title, title, "post", 7, "standard", DATE, 0, content);
    }

    @Test
    void theHeadingCarriesTheEntryTitleAndTheDocumentTitleThePlainOne() {
        String html = FallbackPage.singular(new FallbackPage.Singular(
                "Site", "Secret recipe", "Protected: Secret recipe", "post", 7, "standard", DATE, 0, Optional.empty()));
        assertThat(html)
                .contains("<title>Secret recipe &#8211; Site</title>")
                .contains("<h1 class=\"entry-title\">Protected: Secret recipe</h1>");
    }

    @Test
    void entryTitlesArePrefixedAsGetTheTitlePrefixesThem() {
        // php postpass-fix.php: get_the_title on the front end; "0" is no password, a password outranks private
        assertThat(FallbackPage.entryTitle("Secret recipe", "s3", "publish")).isEqualTo("Protected: Secret recipe");
        assertThat(FallbackPage.entryTitle("Secret recipe", "0", "publish")).isEqualTo("Secret recipe");
        assertThat(FallbackPage.entryTitle("Secret recipe", " ", "publish")).isEqualTo("Protected: Secret recipe");
        assertThat(FallbackPage.entryTitle("Secret recipe", "", "publish")).isEqualTo("Secret recipe");
        assertThat(FallbackPage.entryTitle("Secret recipe", "", "private")).isEqualTo("Private: Secret recipe");
        assertThat(FallbackPage.entryTitle("Secret recipe", "s3", "private")).isEqualTo("Protected: Secret recipe");
        assertThat(FallbackPage.entryTitle("Secret recipe", "0", "private")).isEqualTo("Private: Secret recipe");
        assertThat(FallbackPage.entryTitle("Secret recipe", "s3", "draft")).isEqualTo("Protected: Secret recipe");
        assertThat(FallbackPage.entryTitle("", "s3", "publish")).isEqualTo("Protected: ");
    }

    @Test
    void aTitleIsEscapedInTheDocumentTitleAndTheHeading() {
        String html = FallbackPage.singular(post("Site & Co", "<script>x</script>", Optional.of("<p>body</p>")));
        assertThat(html)
                .contains("<title>&lt;script&gt;x&lt;/script&gt; &#8211; Site &amp; Co</title>")
                .contains("<h1 class=\"entry-title\">&lt;script&gt;x&lt;/script&gt;</h1>")
                .contains("<p class=\"site-title\">Site &amp; Co</p>")
                .doesNotContain("<script>")
                .contains("<div class=\"entry-content\"><p>body</p></div>")
                .contains("<time datetime=\"2025-11-20T10:15:30+00:00\">November 20, 2025</time>")
                .contains("<body class=\"single single-post postid-7 single-format-standard\">")
                .startsWith("<!DOCTYPE html>\n<html lang=\"en-US\">");
    }

    @Test
    void entitiesAlreadyWrittenAreKept() {
        assertThat(FallbackPage.escape("It&#8217;s &amp; &hellip; &#x1F600; & &#; &1; \"q\" 'a'"))
                .isEqualTo("It&#8217;s &amp; &hellip; &#x1F600; &amp; &amp;#; &amp;1; &quot;q&quot; &#039;a&#039;");
    }

    @Test
    void aLockedPostHasAnEmptyContent() {
        assertThat(FallbackPage.singular(post("Site", "Locked", Optional.empty())))
                .contains("<div class=\"entry-content\"></div>");
    }

    @Test
    void anEmptyTitleLeavesTheSiteNameAlone() {
        assertThat(FallbackPage.singular(post("Site", "", Optional.of(""))))
                .contains("<title>Site</title>")
                .contains("<h1 class=\"entry-title\"></h1>");
    }

    @Test
    void aListEscapesItsTitlesAndLinks() {
        String html = FallbackPage.list(
                "Site",
                "archive",
                List.of(new FallbackPage.Item("http://wp.test/?p=1&x=\"", "<b>One</b>")),
                Optional.of("http://wp.test/"),
                Optional.empty());
        assertThat(html)
                .contains("<li><a href=\"http://wp.test/?p=1&amp;x=&quot;\">&lt;b&gt;One&lt;/b&gt;</a></li>")
                .contains("<nav class=\"pagination\"><a class=\"prev\" href=\"http://wp.test/\">")
                .doesNotContain("class=\"next\"");
        assertThat(FallbackPage.list("Site", "home blog", List.of(), Optional.empty(), Optional.empty()))
                .doesNotContain("pagination");
    }

    @Test
    void notFoundKeepsTheM2Text() {
        assertThat(FallbackPage.notFound("Site"))
                .contains("<title>Page not found &#8211; Site</title>")
                .contains("<body class=\"error404\">")
                .contains("<h1 class=\"entry-title\">Page not found</h1>");
    }
}
