package org.quarkpress.restwp.runtime.oembed;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The building and post-processing of a provider's {@code html}: WordPress's untrusted filter (only a first
 * blockquote and a first iframe survive, with a secret, the sandbox attributes and the hidden style), the title put
 * into every iframe, and the newline stripping that spares {@code pre} elements.
 */
class OEmbedHtmlTest {

    private static final String SECRET = "s3cr3t";

    private static final String IFRAME = "<iframe class=\"wp-embedded-content\" sandbox=\"allow-scripts\""
            + " security=\"restricted\" src=\"https://p.invalid/e#?secret=s3cr3t\" data-secret=\"s3cr3t\"></iframe>";

    @Test
    void theUntrustedFilterTable() {
        // a script is dropped, only the iframe survives, with the secret and the sandbox attributes
        assertThat(OEmbedHtml.untrusted(
                        "<script>alert(1)</script><iframe src=\"https://p.invalid/e\"></iframe>", SECRET))
                .contains(IFRAME);
        // srcdoc and onload are not among the allowed attributes
        assertThat(OEmbedHtml.untrusted(
                        "<iframe src=\"https://p.invalid/e\" srcdoc=\"hello\" onload=\"alert(1)\"></iframe>", SECRET))
                .contains(IFRAME);
        // width, height, frameborder, marginwidth, marginheight, scrolling and title are kept; others dropped
        assertThat(OEmbedHtml.untrusted(
                        "<iframe src=\"https://p.invalid/e\" width=\"1\" height=\"2\" frameborder=\"0\""
                                + " marginwidth=\"0\" marginheight=\"0\" scrolling=\"no\" title=\"T\""
                                + " allow=\"camera\" allowfullscreen></iframe>",
                        SECRET))
                .contains("<iframe class=\"wp-embedded-content\" sandbox=\"allow-scripts\" security=\"restricted\""
                        + " src=\"https://p.invalid/e#?secret=s3cr3t\" data-secret=\"s3cr3t\" width=\"1\""
                        + " height=\"2\" frameborder=\"0\" marginwidth=\"0\" marginheight=\"0\" scrolling=\"no\""
                        + " title=\"T\"></iframe>");
        // a javascript: href inside the blockquote loses its protocol (kses), the blockquote keeps the link
        Optional<String> quoted = OEmbedHtml.untrusted(
                "<blockquote><a href=\"javascript:alert(1)\" onclick=\"x()\">x</a></blockquote>"
                        + "<iframe src=\"https://p.invalid/e\"></iframe>",
                SECRET);
        assertThat(quoted)
                .contains("<blockquote class=\"wp-embedded-content\" data-secret=\"s3cr3t\"><a href=\"alert(1)\">x</a>"
                        + "</blockquote><iframe class=\"wp-embedded-content\" sandbox=\"allow-scripts\""
                        + " security=\"restricted\" style=\"position: absolute; visibility: hidden;\""
                        + " src=\"https://p.invalid/e#?secret=s3cr3t\" data-secret=\"s3cr3t\"></iframe>");
        assertThat(quoted.get()).doesNotContain("javascript:").doesNotContain("onclick");
        // two iframes: the last closed one is kept (WordPress's greedy extraction)
        assertThat(OEmbedHtml.untrusted(
                        "<iframe src=\"https://p.invalid/2\"></iframe><iframe src=\"https://p.invalid/e\"></iframe>",
                        SECRET))
                .contains(IFRAME);
        assertThat(OEmbedHtml.untrusted(
                        "<iframe src=\"https://p.invalid/2\"></iframe><iframe src=\"https://p.invalid/e\"></iframe>"
                                + "<iframe src=\"https://p.invalid/open\">",
                        SECRET))
                .contains(IFRAME);
        // the kept iframe must close right after its tag: content inside it (a fallback text) is no match, and the
        // last iframe followed by "></iframe>" wins over a later one with content
        assertThat(OEmbedHtml.untrusted("<iframe src=\"https://p.invalid/e\">fallback</iframe>", SECRET))
                .isEmpty();
        assertThat(OEmbedHtml.untrusted(
                        "<iframe src=\"https://p.invalid/e\"></iframe><iframe src=\"https://p.invalid/2\">text"
                                + "</iframe>",
                        SECRET))
                .contains(IFRAME);
        // an iframe without src is kept and sandboxed, but gets no secret
        assertThat(OEmbedHtml.untrusted("<iframe width=\"1\"></iframe>", SECRET))
                .contains("<iframe class=\"wp-embedded-content\" sandbox=\"allow-scripts\" security=\"restricted\""
                        + " width=\"1\"></iframe>");
        // a javascript: iframe src loses its protocol too
        assertThat(OEmbedHtml.untrusted("<iframe src=\"javascript:alert(1)\"></iframe>", SECRET)
                        .orElseThrow())
                .doesNotContain("javascript:");
        // no iframe: nothing (the html becomes false)
        assertThat(OEmbedHtml.untrusted("<blockquote>q</blockquote><p>text</p>", SECRET))
                .isEmpty();
        assertThat(OEmbedHtml.untrusted("", SECRET)).isEmpty();
        // a blockquote counts only when it opens the sanitized HTML: text before it drops it, as does one after
        assertThat(OEmbedHtml.untrusted(
                        "<p>intro</p><blockquote><p>q</p></blockquote><div><iframe src=\"https://p.invalid/e\">"
                                + "</iframe></div><blockquote>after</blockquote>",
                        SECRET))
                .contains(IFRAME);
        assertThat(OEmbedHtml.untrusted(
                        "<blockquote><p>q</p></blockquote>\n<iframe src=\"https://p.invalid/e\"></iframe>", SECRET))
                .contains("<blockquote class=\"wp-embedded-content\" data-secret=\"s3cr3t\">q</blockquote>"
                        + "<iframe class=\"wp-embedded-content\" sandbox=\"allow-scripts\""
                        + " security=\"restricted\" style=\"position: absolute; visibility: hidden;\""
                        + " src=\"https://p.invalid/e#?secret=s3cr3t\" data-secret=\"s3cr3t\"></iframe>");
        // an iframe written in capitals (kses keeps the case) is still found, sandboxed and given its secret, the
        // opening tag rewritten in lower case; WordPress's extraction would not find it (README D55)
        assertThat(OEmbedHtml.untrusted("<IFRAME SRC=\"https://p.invalid/e\"></IFRAME>", SECRET))
                .contains("<iframe class=\"wp-embedded-content\" sandbox=\"allow-scripts\" security=\"restricted\""
                        + " src=\"https://p.invalid/e#?secret=s3cr3t\" data-secret=\"s3cr3t\"></IFRAME>");
    }

    @Test
    void titledInsertsTheTitleIntoEveryIframe() {
        // no title attribute anywhere: the provider's title, escaped, right after "<iframe "
        assertThat(OEmbedHtml.titled(
                        "<iframe src=\"a\"></iframe><p>x</p><iframe src=\"b\"></iframe>", "Tom & \"Jerry\""))
                .isEqualTo("<iframe title=\"Tom &amp; &quot;Jerry&quot;\" src=\"a\"></iframe><p>x</p>"
                        + "<iframe title=\"Tom &amp; &quot;Jerry&quot;\" src=\"b\"></iframe>");
        // the first iframe's own title wins over the provider's, and replaces every iframe's title attribute
        assertThat(OEmbedHtml.titled(
                        "<iframe src=\"a\" title=\"Old\"></iframe><iframe TITLE='Other' src=\"b\"></iframe>", "New"))
                .isEqualTo("<iframe title=\"Old\" src=\"a\"></iframe><iframe title=\"Old\" TITLE='Other' src=\"b\">"
                        + "</iframe>");
        // an empty (or "0") own title does not count: the provider's title replaces it
        assertThat(OEmbedHtml.titled("<iframe title=\"\" src=\"a\"></iframe>", "New"))
                .isEqualTo("<iframe title=\"New\" src=\"a\"></iframe>");
        assertThat(OEmbedHtml.titled("<iframe title=\"0\" src=\"a\"></iframe>", "New"))
                .isEqualTo("<iframe title=\"New\" src=\"a\"></iframe>");
        assertThat(OEmbedHtml.titled("<iframe title=\"\" src=\"a\"></iframe>", ""))
                .isEqualTo("<iframe title=\"\" src=\"a\"></iframe>");
        // nothing to say: unchanged
        assertThat(OEmbedHtml.titled("<iframe src=\"a\"></iframe>", "")).isEqualTo("<iframe src=\"a\"></iframe>");
        assertThat(OEmbedHtml.titled("<p>no frame</p>", "T")).isEqualTo("<p>no frame</p>");
        // an iframe written in capitals counts too
        assertThat(OEmbedHtml.titled("<IFRAME src=\"a\"></IFRAME>", "T"))
                .isEqualTo("<IFRAME title=\"T\" src=\"a\"></IFRAME>");
    }

    @Test
    void stripNewlinesKeepsPreNewlines() {
        assertThat(OEmbedHtml.stripNewlines("<p>a\nb</p>\r\n<pre>x\ny\r\n</pre>\nz<PRE class=\"c\">\nq</PRE>\n"))
                .isEqualTo("<p>ab</p><pre>x\ny\r\n</pre>z<PRE class=\"c\">\nq</PRE>");
        assertThat(OEmbedHtml.stripNewlines("no newline")).isEqualTo("no newline");
        // only a line feed triggers the stripping, and a lone carriage return stays
        assertThat(OEmbedHtml.stripNewlines("a\rb")).isEqualTo("a\rb");
        assertThat(OEmbedHtml.stripNewlines("a\r\nb\rc\n")).isEqualTo("ab\rc");
        // an unclosed pre spares nothing
        assertThat(OEmbedHtml.stripNewlines("a\n<pre>b\nc")).isEqualTo("a<pre>bc");
    }

    @Test
    void buildByType() {
        ObjectNode photo = JsonNodeFactory.instance.objectNode();
        photo.put("type", "photo");
        photo.put("url", "https://p.invalid/i.jpg?a=1&b=2");
        photo.put("width", 10);
        photo.put("height", "20");
        photo.put("title", "A \"photo\" & more");
        assertThat(OEmbedHtml.build(photo, "https://p.invalid/page?x=1&y=2"))
                .contains(
                        "<a href=\"https://p.invalid/page?x=1&#038;y=2\"><img src=\"https://p.invalid/i.jpg?a=1&#038;b=2\""
                                + " alt=\"A &quot;photo&quot; &amp; more\" width=\"10\" height=\"20\" /></a>");
        // a photo whose URL carries a script protocol loses it (the URL is sanitized as WordPress escapes it)
        photo.put("url", "javascript:alert(1)");
        assertThat(OEmbedHtml.build(photo, "javascript:alert(2)").orElseThrow())
                .doesNotContain("javascript:")
                .startsWith("<a href=\"");
        photo.put("url", "https://p.invalid/i.jpg?a=1&b=2");
        // a photo needs its url and numeric sizes, none of them PHP-empty
        photo.put("width", "0");
        assertThat(OEmbedHtml.build(photo, "https://p.invalid/page")).isEmpty();
        photo.put("width", 0);
        assertThat(OEmbedHtml.build(photo, "https://p.invalid/page")).isEmpty();
        photo.put("width", "wide");
        assertThat(OEmbedHtml.build(photo, "https://p.invalid/page")).isEmpty();
        photo.put("width", 10);
        photo.remove("url");
        assertThat(OEmbedHtml.build(photo, "https://p.invalid/page")).isEmpty();
        photo.put("url", 5);
        assertThat(OEmbedHtml.build(photo, "https://p.invalid/page")).isEmpty();
        // video and rich: the html string as it is
        ObjectNode rich = JsonNodeFactory.instance.objectNode();
        rich.put("type", "rich");
        rich.put("html", "<iframe src=\"x\"></iframe>\n");
        assertThat(OEmbedHtml.build(rich, "u")).contains("<iframe src=\"x\"></iframe>\n");
        rich.put("type", "video");
        assertThat(OEmbedHtml.build(rich, "u")).contains("<iframe src=\"x\"></iframe>\n");
        // an html that is not a string gives nothing
        rich.put("html", 5);
        assertThat(OEmbedHtml.build(rich, "u")).isEmpty();
        rich.remove("html");
        assertThat(OEmbedHtml.build(rich, "u")).isEmpty();
        // link: a link to the URL with the title as text, only with a title
        ObjectNode link = JsonNodeFactory.instance.objectNode();
        link.put("type", "link");
        link.put("title", "T <b>");
        assertThat(OEmbedHtml.build(link, "https://p.invalid/l?a=1&b=2"))
                .contains("<a href=\"https://p.invalid/l?a=1&#038;b=2\">T &lt;b&gt;</a>");
        link.put("title", "");
        assertThat(OEmbedHtml.build(link, "https://p.invalid/l")).isEmpty();
        link.put("title", "0");
        assertThat(OEmbedHtml.build(link, "https://p.invalid/l")).isEmpty();
        link.remove("title");
        assertThat(OEmbedHtml.build(link, "https://p.invalid/l")).isEmpty();
        // anything else: nothing
        ObjectNode other = JsonNodeFactory.instance.objectNode();
        other.put("type", "audio");
        other.put("html", "<p>x</p>");
        assertThat(OEmbedHtml.build(other, "u")).isEmpty();
        other.remove("type");
        assertThat(OEmbedHtml.build(other, "u")).isEmpty();
    }
}
