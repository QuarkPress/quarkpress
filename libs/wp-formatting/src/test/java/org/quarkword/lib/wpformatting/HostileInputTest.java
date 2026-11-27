package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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
