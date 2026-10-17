package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Linear time and bounded allocation on inputs built to make naive implementations quadratic. Every test checks
 * the output as well as the time. Expected values were verified with WordPress at a smaller scale (the same rule
 * applies at any size), except where a test says otherwise.
 */
class HostileInputTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final int MIB = 1 << 20;
    private static final Kses WP = Kses.wordPress();
    private static final Kses BARE = Kses.builder().preFilters(false).build();

    private static String timed(Supplier<String> work) {
        return assertTimeoutPreemptively(LIMIT, work::get);
    }

    @Test
    void aMillionLessThanSigns() {
        // Harness-verified at 20 000: bare kses removes them all, WordPress mode escapes each one.
        String in = "<".repeat(1_000_000);
        assertThat(timed(() -> BARE.post(in))).isEmpty();
        assertThat(timed(() -> WP.post(in))).isEqualTo("&lt;".repeat(1_000_000));
    }

    @Test
    void aHundredThousandNestedTags() {
        String in = "<b>".repeat(100_000) + "x" + "</b>".repeat(100_000);
        assertThat(timed(() -> BARE.post(in))).isEqualTo(in);
        assertThat(timed(() -> WP.post(in))).isEqualTo(in);
    }

    @Test
    void tenMegabyteAttributeValues() {
        String title = "<a title=\"" + "x".repeat(10_000_000) + "\">y</a>";
        assertThat(timed(() -> WP.post(title))).isEqualTo(title);
        String href = "<a href=\"http://x/" + "a:".repeat(5_000_000) + "\">y</a>";
        assertThat(timed(() -> WP.post(href))).isEqualTo(href);
        // WordPress drops the last semicolon of the declaration list.
        String style = "<p style=\"" + "color:red;".repeat(1_000_000) + "\">y</p>";
        assertThat(timed(() -> WP.post(style)))
                .isEqualTo("<p style=\"" + "color:red;".repeat(999_999) + "color:red\">y</p>");
    }

    @Test
    void entityFloods() {
        assertThat(timed(() -> WP.post("&".repeat(1_000_000)))).isEqualTo("&amp;".repeat(1_000_000));
        assertThat(timed(() -> WP.post("&amp;".repeat(1_000_000)))).isEqualTo("&amp;".repeat(1_000_000));
        // WordPress decodes the references inside the attribute value: &#58; is a colon, &copy; and &notin; stay
        // as the characters they stand for.
        String references = "<a title=\"" + "&#58;&copy;&notin;".repeat(300_000) + "\">x</a>";
        assertThat(timed(() -> WP.post(references)))
                .isEqualTo("<a title=\"" + ":\u00a9\u2209".repeat(300_000) + "\">x</a>");
    }

    @Test
    void schemesThatNeverSettle() {
        // Harness-verified at 20 000 colons: six passes are not enough, so the value is emptied.
        assertThat(timed(() -> BARE.post("<a href=\"" + "a:".repeat(500_000) + "\">x</a>")))
                .isEqualTo("<a href=\"\">x</a>");
    }

    @Test
    void commentsWithoutEnds() {
        // Harness-verified at 20 000 repetitions.
        String in = "<!--x>".repeat(200_000) + "\nz";
        assertThat(timed(() -> BARE.post(in))).isEqualTo("<!--x&gt;-->".repeat(200_000) + "\nz");
        // WordPress mode escapes each opener (harness-verified at 20 repetitions).
        assertThat(timed(() -> WP.post("<!--".repeat(250_000)))).isEqualTo("&lt;!--".repeat(250_000));
        // Deliberate difference (README, #9): WordPress loops forever on a comment like this one; QuarkPress drops
        // the comment after a bounded number of rounds, so this expected value comes from the ruling, not from
        // WordPress.
        assertThat(timed(() -> BARE.post("<!-- " + "<!a<b>".repeat(50_000) + " -->")))
                .isEmpty();
    }

    @Test
    void manyAttributesAndDuplicates() {
        String in = "<p" + " class=\"a\"".repeat(200_000) + " data-x".repeat(200_000) + ">x</p>";
        assertThat(timed(() -> WP.post(in))).isEqualTo("<p class=\"a\" data-x>x</p>");
    }

    @Test
    void blockFloods() {
        // The block attribute filter keeps the escaped JSON as it is (harness-verified at 50 repetitions).
        String in = "<!-- wp:p {\"a\":\"\\u003cb\\u003e\"} /-->".repeat(50_000);
        assertThat(timed(() -> WP.post(in))).isEqualTo(in);
        // WordPress closes every unclosed block inside the recovered text: opener j is followed by the text and one
        // closer, with j openers in front (harness-verified at 4 openers). Deliberate difference (README, #11): the
        // j - 1 openers inside the recovered text of block j carry unfiltered attributes, so QuarkPress neutralizes
        // them (a leading &lt;, then kses escapes the rest). That part of the expected value comes from the ruling,
        // not from WordPress.
        int n = 500;
        StringBuilder expected = new StringBuilder();
        for (int j = 1; j <= n; j++) {
            expected.append("<!-- wp:p -->")
                    .append("&lt;!-- wp:p --&gt;".repeat(j - 1))
                    .append("x<!-- /wp:p -->");
        }
        String unclosed = "<!-- wp:p -->".repeat(n) + "x";
        assertThat(timed(() -> WP.post(unclosed))).isEqualTo(expected.toString());
    }

    @Test
    void allocationStaysProportionalToInput() {
        String in = "<p style=\"color:red\" title=\"" + "&amp;x".repeat(200_000) + "\">" + "<b>t</b> ".repeat(100_000)
                + "</p>";
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        String out = WP.post(in);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertThat(allocated).isLessThan(200L * in.length());
        assertThat(out).isEqualTo(in);
    }

    // The helpers below take one mebibyte each; the expected values were verified with WordPress at 400 repetitions
    // (wp_kses_no_null, wp_kses_html_error, wp_kses_decode_entities). wellFormed has no WordPress counterpart: PHP
    // strings are bytes, and its result follows from its contract (every unpaired surrogate becomes U+FFFD).

    @Test
    void removeControlsOnAMebibyte() {
        assertThat(timed(() -> NullFilter.removeControls("\0".repeat(MIB)))).isEmpty();
        assertThat(timed(() -> NullFilter.removeControls("a\u0001b".repeat(MIB / 3))))
                .isEqualTo("ab".repeat(MIB / 3));
        assertThat(timed(() -> NullFilter.removeControls("\u0001\u001f\u000b\t".repeat(MIB / 4))))
                .isEqualTo("\t".repeat(MIB / 4));
        String clean = "plain text\r\n".repeat(MIB / 12);
        assertThat(timed(() -> NullFilter.removeControls(clean))).isEqualTo(clean);
    }

    @Test
    void removeSlashZeroOnAMebibyte() {
        assertThat(timed(() -> NullFilter.removeSlashZero("\\".repeat(MIB) + "0")))
                .isEmpty();
        assertThat(timed(() -> NullFilter.removeSlashZero("\\0x".repeat(MIB / 3))))
                .isEqualTo("x".repeat(MIB / 3));
        assertThat(timed(() -> NullFilter.removeSlashZero("\\".repeat(MIB)))).isEqualTo("\\".repeat(MIB));
        assertThat(timed(() -> NullFilter.removeSlashZero("\\".repeat(MIB) + "1")))
                .isEqualTo("\\".repeat(MIB) + "1");
        assertThat(timed(() -> NullFilter.removeSlashZero("\\" + "0".repeat(MIB) + "x")))
                .isEqualTo("x");
    }

    @Test
    void decodeNumericBytesOnAMebibyte() {
        assertThat(timed(() -> TextUtils.decodeNumericBytes("&#65;".repeat(MIB / 5))))
                .isEqualTo("A".repeat(MIB / 5));
        assertThat(timed(() -> TextUtils.decodeNumericBytes("&#x41;".repeat(MIB / 6))))
                .isEqualTo("A".repeat(MIB / 6));
        // Numbers are taken modulo 256.
        assertThat(timed(() -> TextUtils.decodeNumericBytes("&#321;".repeat(MIB / 6))))
                .isEqualTo("A".repeat(MIB / 6));
        assertThat(timed(() -> TextUtils.decodeNumericBytes("&#X141;".repeat(MIB / 7))))
                .isEqualTo("A".repeat(MIB / 7));
        assertThat(timed(() -> TextUtils.decodeNumericBytes("&#".repeat(MIB / 2))))
                .isEqualTo("&#".repeat(MIB / 2));
        String noSemicolon = "&#" + "9".repeat(MIB);
        assertThat(timed(() -> TextUtils.decodeNumericBytes(noSemicolon))).isEqualTo(noSemicolon);
        String noSemicolonHex = "&#x" + "f".repeat(MIB);
        assertThat(timed(() -> TextUtils.decodeNumericBytes(noSemicolonHex))).isEqualTo(noSemicolonHex);
        assertThat(timed(() -> TextUtils.decodeNumericBytes("&#65&#x41;&#x;&#;".repeat(MIB / 17))))
                .isEqualTo("&#65A&#x;&#;".repeat(MIB / 17));
    }

    @Test
    void htmlErrorOnAMebibyte() {
        assertThat(timed(() -> TextUtils.htmlError("\"".repeat(MIB)))).isEmpty();
        assertThat(timed(() -> TextUtils.htmlError("\"".repeat(MIB + 1)))).isEmpty();
        assertThat(timed(() -> TextUtils.htmlError("x".repeat(MIB)))).isEmpty();
        assertThat(timed(() -> TextUtils.htmlError("x" + " ".repeat(MIB) + "y")))
                .isEqualTo("y");
        assertThat(timed(() -> TextUtils.htmlError("x ".repeat(MIB / 2)))).isEqualTo("x ".repeat(MIB / 2 - 1));
    }

    @Test
    void wellFormedOnAMebibyte() {
        // The replacement character stands for every unpaired surrogate; pairs stay.
        assertThat(timed(() -> TextUtils.wellFormed("\ud800".repeat(MIB)))).isEqualTo("\ufffd".repeat(MIB));
        assertThat(timed(() -> TextUtils.wellFormed("\udc00".repeat(MIB)))).isEqualTo("\ufffd".repeat(MIB));
        String pairs = "\ud83d\ude00".repeat(MIB / 2);
        assertThat(timed(() -> TextUtils.wellFormed(pairs))).isEqualTo(pairs);
        assertThat(timed(() -> TextUtils.wellFormed("a".repeat(MIB) + "\ud83d")))
                .isEqualTo("a".repeat(MIB) + "\ufffd");
    }
}
