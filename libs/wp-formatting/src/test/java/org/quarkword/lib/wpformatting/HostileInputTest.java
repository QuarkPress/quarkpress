package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class HostileInputTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);

    /** Runs {@code work} under the time limit and bounds what it allocates on its own thread. */
    private static <T> T bounded(String input, Supplier<T> work) {
        return assertTimeoutPreemptively(LIMIT, () -> {
            var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long before = threads.getCurrentThreadAllocatedBytes();
            T result = work.get();
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            assertThat(allocated)
                    .as("bytes allocated for %d chars", input.length())
                    .isLessThan(200L * input.length() + 10_000_000L);
            return result;
        });
    }

    // Task 1
    @Test
    void htmlSplitAndReplaceAreLinear() {
        String lessThans = "<".repeat(1_000_000);
        // a "<" chunk runs to the next ">" or the end, so the whole run is one markup chunk (WordPress's answer)
        assertThat(bounded(lessThans, () -> Formatting.htmlSplit(lessThans))).containsExactly("", lessThans, "");
        assertThat(bounded(lessThans, () -> Formatting.replaceInHtmlTags(lessThans, Map.of("<", "&lt;"))))
                .isEqualTo("&lt;".repeat(1_000_000));

        String tags = "<a>".repeat(300_000);
        assertThat(bounded(tags, () -> Formatting.htmlSplit(tags))).hasSize(600_001);

        String openers = "<!--".repeat(250_000);
        assertThat(bounded(openers, () -> Formatting.htmlSplit(openers))).hasSize(3);

        String cdata = "<![CDATA[".repeat(100_000);
        assertThat(bounded(cdata, () -> Formatting.htmlSplit(cdata))).hasSize(3);

        String pairs = "<a " + "xy".repeat(500_000) + ">";
        assertThat(bounded(pairs, () -> Formatting.replaceInHtmlTags(pairs, Map.of("x", "1", "xy", "2", "y", "3"))))
                .isEqualTo("<a " + "2".repeat(500_000) + ">");
    }

    // final review: the replacement table is built once per call, not once per markup chunk
    @Test
    void replaceInHtmlTagsBuildsItsTableOncePerCall() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("a", "b");
        for (int i = 0; i < 999; i++) {
            table.put("a" + i, "never"); // shares the "a" prefix, so each tag walks one step further and gives up
        }
        assertThat(table).hasSize(1_000);
        String tags = "<a>".repeat(300_000);
        assertThat(bounded(tags, () -> Formatting.replaceInHtmlTags(tags, table)))
                .isEqualTo("<b>".repeat(300_000));
    }

    // Task 2
    @Test
    void autopIsLinear() {
        String spaces = "a" + " ".repeat(200_000) + "b";
        assertThat(bounded(spaces, () -> Formatting.autop(spaces))).isEqualTo("<p>a" + " ".repeat(200_000) + "b</p>\n");

        String lessThans = "< x\n\n".repeat(50_000);
        String swallowed = bounded(lessThans, () -> Formatting.autop(lessThans));
        assertThat(count(swallowed, "<p>")).isEqualTo(1);
        assertThat(count(swallowed, "\n")).isEqualTo(100_002);

        String scripts = "<script>".repeat(20_000) + "x\n".repeat(1_000);
        assertThat(bounded(scripts, () -> Formatting.autop(scripts))).hasSize(168_001);

        String quotes = "<blockquote " + "\"a\" ".repeat(50_000);
        assertThat(bounded(quotes, () -> Formatting.autop(quotes))).hasSize(200_015);

        String words = "word ".repeat(1_000_000);
        assertThat(bounded(words, () -> Formatting.autop(words))).isEqualTo("<p>" + words + "</p>\n");

        String lines = "Line of text\n".repeat(20_000) + "\n" + "Para\n\n".repeat(20_000);
        String paragraphs = bounded(lines, () -> Formatting.autop(lines));
        assertThat(paragraphs).hasSize(620_001);
        assertThat(count(paragraphs, "<br />")).isEqualTo(19_999);
        assertThat(count(paragraphs, "<p>")).isEqualTo(20_001);
        assertThat(md5(paragraphs)).isEqualTo("08f6a21690a04e6b21d8ccf9dc8c6a33");

        // WordPress returns "" for this size (D1); its answer at 1/10 size pins the shape, the full size is ours
        String fewListItems = "<p><li".repeat(10_000);
        String fewItemsOut = Formatting.autop(fewListItems);
        assertThat(fewItemsOut).hasSize(50_006);
        assertThat(md5(fewItemsOut)).isEqualTo("8889caabf18324794d4819d3741d9f7a");
        String listItems = "<p><li".repeat(100_000);
        String itemsOut = bounded(listItems, () -> Formatting.autop(listItems));
        assertThat(itemsOut).hasSize(500_006);
        assertThat(md5(itemsOut)).isEqualTo("dcc2d4a21a3c330659984543a41e96e9");

        int depth = 100_000;
        String nested = "<div>".repeat(depth) + "x\n" + "</div>".repeat(depth);
        assertThat(bounded(nested, () -> Formatting.autop(nested)))
                .isEqualTo("<div>\n".repeat(depth - 1) + "<div>x\n" + "</div>\n".repeat(depth));

        String pres = "<pre>".repeat(50_000) + "x" + "</pre>".repeat(50_000);
        assertThat(bounded(pres, () -> Formatting.autop(pres))).isEqualTo("<pre>".repeat(50_000) + "x</pre>\n");

        String placeholders = "<pre wp-pre-tag-1></pre>".repeat(20_000) + "<pre>a</pre><pre>b</pre>";
        assertThat(bounded(placeholders, () -> Formatting.autop(placeholders)))
                .isEqualTo("<pre wp-pre-tag-1></pre>\n".repeat(20_000) + "<pre>a</pre>\n<pre>b</pre>\n");
    }

    // Task 2 fix round: a typed placeholder chain would grow quadratically; the cap refuses it before building it
    @Test
    void preChainIsRefusedBeforeItGrows() {
        StringBuilder chain = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            chain.append("<pre>a<pre wp-pre-tag-").append(i + 1).append("></pre>");
        }
        String input = chain.toString();
        FormattingLimitException refused = bounded(input, () -> {
            try {
                Formatting.autop(input);
                return null;
            } catch (FormattingLimitException e) {
                return e;
            }
        });
        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.allowedLength()).isEqualTo(64L * input.length());
        assertThat(refused.requiredLength()).isGreaterThan(refused.allowedLength());
    }

    // Task 2: WordPress returns "" for the 3 MB case (its backtrack limit); QuarkWord keeps the 1 MB shape
    @Test
    void liParagraphKeepsItsShape() {
        String small = "<li>" + "x".repeat(1_000_000);
        String large = "<li>" + "x".repeat(3_000_000);
        String smallOut = bounded(small, () -> Formatting.autop(small));
        String largeOut = bounded(large, () -> Formatting.autop(large));
        assertThat(largeOut).isEqualTo(smallOut.replace("x".repeat(1_000_000), "x".repeat(3_000_000)));
    }

    // Task 3: B-G179 to B-G181 and the other large texturize inputs (each one is a parity case compared in full)
    @Test
    void texturizeIsLinear() {
        Texturize wordPress = Texturize.wordPress();
        Texturize code =
                Texturize.builder().registeredShortcodes(List.of("code")).build();

        String quotes = "\"a' ".repeat(20_000);
        String curled = bounded(quotes, () -> wordPress.apply(quotes));
        assertThat(curled).hasSize(320_000).isEqualTo("&#8220;a&#8217; ".repeat(20_000));
        String manyQuotes = "\"a' ".repeat(250_000);
        assertThat(bounded(manyQuotes, () -> wordPress.apply(manyQuotes)))
                .isEqualTo("&#8220;a&#8217; ".repeat(250_000));

        String openTag = "<" + "a".repeat(200_000);
        assertThat(bounded(openTag, () -> wordPress.apply(openTag))).isEqualTo(openTag);
        String openComment = "<!--" + "-".repeat(200_000);
        assertThat(bounded(openComment, () -> wordPress.apply(openComment))).isEqualTo(openComment);
        String openShortcode = "[code " + "a".repeat(100_000);
        assertThat(bounded(openShortcode, () -> code.apply(openShortcode))).isEqualTo(openShortcode);

        String pres = "<pre>".repeat(10_000) + "\"a\"";
        assertThat(bounded(pres, () -> wordPress.apply(pres))).isEqualTo(pres);

        String dashes = "-".repeat(100_001);
        assertThat(bounded(dashes, () -> wordPress.apply(dashes))).isEqualTo("&#8212;".repeat(33_333) + "&#8211;");

        String lessThans = "a<".repeat(500_000);
        assertThat(bounded(lessThans, () -> wordPress.apply(lessThans))).isEqualTo(lessThans);

        String codes = "<code>".repeat(10_000) + "x";
        assertThat(bounded(codes, () -> wordPress.apply(codes))).isEqualTo(codes);
        String shortcodes = "[code]".repeat(200_000);
        assertThat(bounded(shortcodes, () -> code.apply(shortcodes))).isEqualTo(shortcodes);
    }

    // Task 3: more adversarial wptexturize shapes, each checked against WordPress at this size
    @Test
    void texturizeAdversarialShapes() {
        Texturize wordPress = Texturize.wordPress();
        Texturize code =
                Texturize.builder().registeredShortcodes(List.of("code")).build();

        String singles = "'".repeat(1_000_000);
        assertThat(bounded(singles, () -> wordPress.apply(singles))).isEqualTo("&#8221;".repeat(500_000));
        String doubles = "\"".repeat(1_000_000);
        assertThat(bounded(doubles, () -> wordPress.apply(doubles))).isEqualTo("&#8220;" + "&#8221;".repeat(999_999));
        String ampersands = "&".repeat(1_000_000);
        assertThat(bounded(ampersands, () -> wordPress.apply(ampersands))).isEqualTo("&#038;".repeat(1_000_000));
        String brackets = "[".repeat(1_000_000);
        assertThat(bounded(brackets, () -> code.apply(brackets))).isEqualTo(brackets);

        // one opening quote per sentence, each with a closing quote and a prime to decide
        String singleSentences = "' a' b9' ".repeat(125_000);
        assertThat(bounded(singleSentences, () -> wordPress.apply(singleSentences)))
                .isEqualTo("&#8216; a&#8217; b9&#8242; ".repeat(125_000));
        String doubleSentences = "\"a\" 9\" ".repeat(125_000);
        assertThat(bounded(doubleSentences, () -> wordPress.apply(doubleSentences)))
                .isEqualTo("&#8220;a&#8221; 9&#8243; ".repeat(125_000));
        String mixed = "\"a\" 'b' 9' 10\" -- x 1x2 & ".repeat(30_000);
        String mixedOut = bounded(mixed, () -> wordPress.apply(mixed));
        assertThat(mixedOut).hasSize(2_310_000);
        assertThat(md5(mixedOut)).isEqualTo("be89d3b286f8dc5fb826f0a5013fec15");

        // WordPress fails on these two (D5, D6) but completes one size below them, with the same shape
        String comment = "<!--" + "-".repeat(1_000_000) + "-->\"a\"";
        assertThat(bounded(comment, () -> wordPress.apply(comment)))
                .isEqualTo("<!--" + "-".repeat(1_000_000) + "-->&#8220;a&#8221;");
        String product = "1x1" + "1".repeat(1_000_000) + "a";
        assertThat(bounded(product, () -> wordPress.apply(product))).isEqualTo(product);
    }

    // Task 4
    @Test
    void convertCharsAndCapitalPAreLinear() {
        // the full output is compared with WordPress's by the parity case t4-amps-1mb
        String ampersands = "&".repeat(1_000_000);
        assertThat(bounded(ampersands, () -> Formatting.convertChars(ampersands)))
                .hasSize(3_500_000)
                .isEqualTo("&#038;&".repeat(500_000));

        String words = "Wordpress".repeat(120_000);
        assertThat(bounded(words, () -> Formatting.capitalPDangit(words, CapitalPMode.SIMPLE)))
                .isEqualTo("WordPress".repeat(120_000));
        // judicious mode: no occurrence has one of its prefixes
        assertThat(bounded(words, () -> Formatting.capitalPDangit(words, CapitalPMode.JUDICIOUS)))
                .isSameAs(words);

        // the shapes of the parity cases t4-names-1mb and t4-prefixes-content (WordPress's answers)
        String names = "&ab;&#1;&&amp;&é&".repeat(60_000);
        assertThat(bounded(names, () -> Formatting.convertChars(names)))
                .isEqualTo("&ab;&#1;&&amp;&#038;é&".repeat(60_000));
        String prefixes = " Wordpress(Wordpress>Wordpress&#8220;Wordpress".repeat(30_000);
        assertThat(bounded(prefixes, () -> Formatting.capitalPDangit(prefixes, CapitalPMode.JUDICIOUS)))
                .isEqualTo(" WordPress(WordPress>WordPress&#8220;WordPress".repeat(30_000));
    }

    // Task 5
    @Test
    void smiliesAreLinear() {
        Smilies smilies = Smilies.wordPress("https://example.com/wp-includes/images/smilies/");
        String spaced = ":) ".repeat(350_000);
        assertThat(bounded(spaced, () -> smilies.apply(spaced))).isEqualTo("\uD83D\uDE42 ".repeat(350_000));
        String colons = ":".repeat(1_000_000);
        assertThat(bounded(colons, () -> smilies.apply(colons))).isEqualTo(colons);
        String pres = "<pre>".repeat(200_000) + ":)";
        assertThat(bounded(pres, () -> smilies.apply(pres))).isEqualTo(pres);
        String lessThans = "<".repeat(1_000_000);
        assertThat(bounded(lessThans, () -> smilies.apply(lessThans))).isEqualTo(lessThans);

        // the shapes of the parity cases t5-tags-1mb, t5-touching-1mb and t5-mixed-1mb (WordPress's answers)
        String tags = "<b>:)</b>".repeat(110_000);
        assertThat(bounded(tags, () -> smilies.apply(tags))).isEqualTo("<b>\uD83D\uDE42</b>".repeat(110_000));
        String touching = ":):)".repeat(250_000);
        assertThat(bounded(touching, () -> smilies.apply(touching))).isEqualTo(touching);
        String mixed = ":mrgreen: &nbsp;:-|&nbsp;\n<code>:)</code>:???:\u00a0".repeat(20_000);
        assertThat(bounded(mixed, () -> smilies.apply(mixed)))
                .isEqualTo(("<img src=\"https://example.com/wp-includes/images/smilies/mrgreen.png\" alt=\":mrgreen:\""
                                + " class=\"wp-smiley\" style=\"height: 1em; max-height: 1em;\" /> &nbsp;\uD83D\uDE10&nbsp;\n"
                                + "<code>:)</code>\uD83D\uDE15\u00a0")
                        .repeat(20_000));
    }

    // Task 6
    @Test
    void excerptsAreLinear() {
        String spaces = " ".repeat(1_000_000) + "a";
        assertThat(bounded(spaces, () -> Excerpts.trimWords(spaces, 55))).isEqualTo("a");
        String words = "w ".repeat(500_000);
        assertThat(bounded(words, () -> Excerpts.trimWords(words, Integer.MAX_VALUE)))
                .isEqualTo("w ".repeat(499_999) + "w");
        String amps = "&".repeat(1_000_000);
        // the cut keeps 999,999 "&"; the last seven are a partial entity
        assertThat(bounded(amps, () -> Excerpts.htmlExcerpt(amps, 999_999))).isEqualTo("&".repeat(999_992));
        String starts = "<sup data-fn=\"".repeat(100_000);
        assertThat(bounded(starts, () -> Excerpts.removeFootnotes(starts))).isEqualTo(starts);
        String chars = "a\uD83D\uDE00\u4E2D ".repeat(250_000);
        assertThat(bounded(
                        chars,
                        () -> Excerpts.trimWords(
                                chars, 500_000, null, WordCountType.CHARACTERS_INCLUDING_SPACES, true)))
                .isEqualTo("a\uD83D\uDE00\u4E2D ".repeat(125_000) + "&hellip;");

        // the shapes of the parity cases t6-unlimited-1mb, t6-footnotes-1mb and t6-near-footnotes-1mb
        assertThat(bounded(words, () -> Excerpts.trimWords(words, -1))).isEqualTo("w ".repeat(499_998) + "w&hellip;");
        String note = "<sup data-fn=\"f\" class=\"fn\"><a href=\"#f\" id=\"f-link\">1</a></sup>";
        String notes = ("x" + note).repeat(15_000);
        assertThat(bounded(notes, () -> Excerpts.removeFootnotes(notes))).isEqualTo("x".repeat(15_000));
        String nearMisses = note.substring(0, note.length() - 1).repeat(15_000);
        assertThat(bounded(nearMisses, () -> Excerpts.removeFootnotes(nearMisses)))
                .isEqualTo(nearMisses);
    }

    // Task 7: A-G161 and larger; b is not nestable, so each new <b> first closes the one before it
    @Test
    void balanceIsLinear() {
        String g161 = "<b>".repeat(20_000) + "x";
        String g161Out = bounded(g161, () -> Formatting.forceBalanceTags(g161));
        assertThat(g161Out).hasSize(140_001).isEqualTo("<b></b>".repeat(19_999) + "<b>x</b>");
        assertThat(count(g161Out, "</b>")).isEqualTo(20_000);
        String large = "<b>".repeat(350_000) + "x";
        assertThat(bounded(large, () -> Formatting.forceBalanceTags(large)))
                .hasSize(2_450_001)
                .isEqualTo("<b></b>".repeat(349_999) + "<b>x</b>");
        String lessThans = "<".repeat(1_000_000);
        assertThat(bounded(lessThans, () -> Formatting.forceBalanceTags(lessThans)))
                .isEqualTo(lessThans);
    }

    // Task 7: the shapes of the t7-* parity cases (WordPress's answers at these sizes, except t7-open-attrs-50k)
    @Test
    void balanceAdversarialShapes() {
        int n = 100_000;
        String deep = "<div>".repeat(n) + "x" + "</div>".repeat(n);
        assertThat(bounded(deep, () -> Formatting.forceBalanceTags(deep))).isEqualTo(deep);
        String upper = "<DIV>".repeat(n) + "x" + "</div>".repeat(n);
        assertThat(bounded(upper, () -> Formatting.forceBalanceTags(upper))).isEqualTo(deep);
        String spans = "<span>".repeat(n) + "x";
        assertThat(bounded(spans, () -> Formatting.forceBalanceTags(spans))).isEqualTo(spans + "</span>".repeat(n));
        String paragraphs = "<p>".repeat(n) + "x";
        assertThat(bounded(paragraphs, () -> Formatting.forceBalanceTags(paragraphs)))
                .isEqualTo("<p></p>".repeat(n - 1) + "<p>x</p>");
        String unclosed = "<i><b>".repeat(50_000) + "x";
        assertThat(bounded(unclosed, () -> Formatting.forceBalanceTags(unclosed)))
                .isEqualTo(unclosed + "</b></i>".repeat(50_000));

        String voids = "<br>".repeat(100_000) + "<img src=x/>".repeat(25_000);
        assertThat(bounded(voids, () -> Formatting.forceBalanceTags(voids)))
                .isEqualTo("<br />".repeat(100_000) + "<img src=x/>".repeat(25_000));

        String attributes = "<a b=\"<i>\">".repeat(n);
        assertThat(bounded(attributes, () -> Formatting.forceBalanceTags(attributes)))
                .isEqualTo("<a b=\"<i>\">" + "</a><a b=\"<i>\">".repeat(n - 1) + "</a>");
        String comments = "<!-- <b> -->".repeat(n);
        assertThat(bounded(comments, () -> Formatting.forceBalanceTags(comments)))
                .isEqualTo("<!-- <b> -->" + "<!-- </b><b> -->".repeat(n - 1) + "</b>");

        String strays = "</b>".repeat(300_000);
        assertThat(bounded(strays, () -> Formatting.forceBalanceTags(strays))).isEmpty();
        String strayUnderDeep = "<b>".repeat(50_000) + "</i>".repeat(50_000);
        assertThat(bounded(strayUnderDeep, () -> Formatting.forceBalanceTags(strayUnderDeep)))
                .isEqualTo("<b></b>".repeat(50_000));
        String crossed = "<b><i>".repeat(50_000) + "</b>".repeat(50_000);
        assertThat(bounded(crossed, () -> Formatting.forceBalanceTags(crossed)))
                .isEqualTo("<b><i>".repeat(50_000) + "</i></b>".repeat(50_000));
        String malformed = "</ b></></A-b><//b></b x></b/>".repeat(30_000);
        assertThat(bounded(malformed, () -> Formatting.forceBalanceTags(malformed)))
                .isEqualTo("</ b></></A-b><//b>".repeat(30_000));

        String longName = "<" + "a".repeat(1_000_000);
        assertThat(bounded(longName, () -> Formatting.forceBalanceTags(longName)))
                .isEqualTo(longName);
        String longAttributes = "<a " + "x".repeat(1_000_000);
        assertThat(bounded(longAttributes, () -> Formatting.forceBalanceTags(longAttributes)))
                .isEqualTo(longAttributes);
        // no ">" anywhere: WordPress takes 16 s on this size, so its parity case is 50,000 openers
        String openAttributes = "<a ".repeat(300_000);
        assertThat(bounded(openAttributes, () -> Formatting.forceBalanceTags(openAttributes)))
                .isEqualTo(openAttributes);

        String escapes = "< !--".repeat(200_000) + "<    !--".repeat(100_000);
        assertThat(bounded(escapes, () -> Formatting.forceBalanceTags(escapes))).isEqualTo("< !--".repeat(300_000));
        String digits = "<3".repeat(500_000);
        assertThat(bounded(digits, () -> Formatting.forceBalanceTags(digits))).isEqualTo("&lt;3".repeat(500_000));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static String md5(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
