package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.quarkpress.lib.wpblocks.Block;
import org.quarkpress.lib.wpblocks.BlockParseException;
import org.quarkpress.lib.wpblocks.WpBlocks;

class PreFiltersTest {

    private static final Kses WP = Kses.wordPress();

    @Test
    void matchesWordPress() {
        Examples.assertFile("kses-wp.json");
    }

    @Test
    void lessThanEscapesSpansWithoutGreaterThan() {
        // R4.2 / R4.3 (verified)
        assertThat(PreFilters.lessThan("a < b")).isEqualTo("a &lt; b");
        assertThat(PreFilters.lessThan("<a href=\"x\"")).isEqualTo("&lt;a href=&quot;x&quot;");
        assertThat(PreFilters.lessThan("<b>x</b")).isEqualTo("<b>x&lt;/b");
        assertThat(PreFilters.lessThan("<<b>>")).isEqualTo("&lt;<b>>");
        String plain = "no tags";
        assertThat(PreFilters.lessThan(plain)).isSameAs(plain);
    }

    @Test
    void tripleDashCommentEndsAreRepairedOnOneLine() {
        assertThat(PreFilters.repairTripleDashComments("<!-- wp:p {\"a\":\"x\"} --->"))
                .isEqualTo("<!-- wp:p {\"a\":\"x\"} -->");
        assertThat(PreFilters.repairTripleDashComments("<!-- x\n--->")).isEqualTo("<!-- x\n--->");
        assertThat(PreFilters.repairTripleDashComments("<!-- a ----->b")).isEqualTo("<!-- a -->b");
    }

    @Test
    void attributeObjectsWhoseKeysBecomeAListStayObjects() {
        // README #7: WordPress writes ["a"] here (lib-wp-blocks D12).
        assertThat(WP.post("<!-- wp:p {\"0\\u003cx\\u003e\":\"a\"} /-->")).isEqualTo("<!-- wp:p {\"0\":\"a\"} /-->");
    }

    @Test
    void templatePartTagNamesThatAreArraysBecomeEmpty() {
        // README #8: WordPress throws a TypeError for a non-empty array or object.
        assertThat(WP.post("<!-- wp:template-part {\"tagName\":[\"x\"]} /-->"))
                .isEqualTo("<!-- wp:template-part {\"tagName\":\"\"} /-->");
        assertThat(WP.post("<!-- wp:template-part {\"tagName\":{\"a\":1}} /-->"))
                .isEqualTo("<!-- wp:template-part {\"tagName\":\"\"} /-->");
    }

    @Test
    void templatePartTagNamesAreCheckedAtEveryDepthAndForEveryType() {
        // Harness-verified (KSES_MODE=wp)
        assertThat(WP.post("<!-- wp:template-part {\"tagName\":\"div\"} /-->"))
                .isEqualTo("<!-- wp:template-part {\"tagName\":\"div\"} /-->");
        assertThat(WP.post("<!-- wp:template-part {\"tagName\":5} /-->"))
                .isEqualTo("<!-- wp:template-part {\"tagName\":\"\"} /-->");
        assertThat(WP.post("<!-- wp:template-part {\"tagName\":true} /-->"))
                .isEqualTo("<!-- wp:template-part {\"tagName\":\"\"} /-->");
        assertThat(WP.post("<!-- wp:template-part {\"x\":{\"tagName\":\"script\"}} /-->"))
                .isEqualTo("<!-- wp:template-part {\"x\":{\"tagName\":\"\"}} /-->");
        assertThat(WP.post("<!-- wp:p {\"x\":{\"tagName\":\"script\"}} /-->"))
                .isEqualTo("<!-- wp:p {\"x\":{\"tagName\":\"script\"}} /-->");
    }

    @Test
    void innerBlocksAndUnclosedBlocksAreFiltered() {
        // Harness-verified (KSES_MODE=wp)
        assertThat(WP.post("<!-- wp:p {\"a\":\"\\u003cscript\\u003ex\"} --><p>t</p>"
                        + "<!-- wp:q {\"b\":\"\\u003cb onclick=1\\u003ey\"} /--><!-- /wp:p -->"))
                .isEqualTo(
                        "<!-- wp:p {\"a\":\"x\"} --><p>t</p><!-- wp:q {\"b\":\"\\u003cb\\u003ey\"} /--><!-- /wp:p -->");
        assertThat(WP.post("<!-- wp:p {\"a\":\"\\u003cscript\\u003ex\"} -->unclosed"))
                .isEqualTo("<!-- wp:p {\"a\":\"x\"} -->unclosed<!-- /wp:p -->");
        // WordPress gives x<!-- wp:p -->x<!-- /wp:p --><!-- wp:p -->x<!-- wp:p -->x<!-- /wp:p -->; the inner opener
        // repeated in the open outer block's text is neutralized (ruling #11, README difference #11).
        assertThat(WP.post("<!-- wp:p -->x<!-- wp:p -->x"))
                .isEqualTo("x<!-- wp:p -->x<!-- /wp:p --><!-- wp:p -->x&lt;!-- wp:p --&gt;x<!-- /wp:p -->");
    }

    @Test
    void attributeValuesAreSanitizedWithoutTheBlockFilter() {
        // Harness-verified (KSES_MODE=wp): WordPress removes the block filter while it runs, so a delimiter
        // inside an attribute value is sanitized as a plain comment.
        String in = "<!-- wp:p {\"a\":\"\\u003c!-- wp:q {\\\"b\\\":\\\"\\u003cscript\\u003ex\\\"} /--\\u003e\"} /-->";
        assertThat(WP.post(in))
                .isEqualTo("<!-- wp:p {\"a\":\"\\u0026lt;!\\u002d\\u002d wp:q {\\u0026quot;b\\u0026quot;:"
                        + "\\u0026quot;x\\u0022} /\\u002d\\u002d\\u0026gt;\"} /-->");
    }

    @Test
    void deeplyNestedBlockAttributesAreFilteredWithoutOverflow() {
        // Deep nesting must not overflow the stack (harness-verified at depth 300)
        int depth = 300;
        String in =
                "<!-- wp:p {\"a\":" + "[".repeat(depth) + "\"\\u003cscript\\u003ex\"" + "]".repeat(depth) + "} /-->";
        String expected = "<!-- wp:p {\"a\":" + "[".repeat(depth) + "\"x\"" + "]".repeat(depth) + "} /-->";
        assertThat(WP.post(in)).isEqualTo(expected);
    }

    @Test
    void bareModeHasNoPreFilters() {
        Kses bare = Kses.builder().preFilters(false).build();
        assertThat(bare.post("a < b")).isEqualTo("a <b>");
        assertThat(WP.post("a < b")).isEqualTo("a &lt; b");
    }

    @Test
    void manyLessThanSignsStayLinear() {
        String in = "<".repeat(1_000_000);
        String out = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> WP.post(in));
        assertThat(out).isEqualTo("&lt;".repeat(1_000_000));
    }

    @Test
    void blockDelimitersAreNeutralized() {
        // Expected values from ruling B1 (README, difference #10)
        assertThat(PreFilters.neutralizeBlockDelimiters(
                        "a<!-- wp:p {\"x\":1} --><!--\t/wp:p --><!--wp:q --><!-- x -->", true))
                .isEqualTo("a&lt;!-- wp:p {\"x\":1} -->&lt;!--\t/wp:p --><!--wp:q --><!-- x -->");
        String plain = "<!-- note --> <p>x</p>";
        assertThat(PreFilters.neutralizeBlockDelimiters(plain, true)).isSameAs(plain);
    }

    @Test
    void onlyCommentsTheBlockParserReadsAsDelimitersAreNeutralized() {
        // Expected values from rulings B1 and #11; the shapes follow lib-wp-blocks' delimiter grammar.
        String notDelimiters =
                "<!-- wp:P --><!-- wp:p x --><!-- wp:p {\"a\":1}--><!-- wp:a/ --><!-- wp:p {\"a\":1} - ->";
        assertThat(PreFilters.neutralizeBlockDelimiters(notDelimiters, true)).isSameAs(notDelimiters);
        String closers = "<!-- /wp:p --><!--\t/wp:q\n-->";
        assertThat(PreFilters.neutralizeBlockDelimiters(closers, false)).isSameAs(closers);
        assertThat(PreFilters.neutralizeBlockDelimiters("<!-- /wp:p /--><!-- /wp:q {\"a\":1} -->", false))
                .isEqualTo("&lt;!-- /wp:p /-->&lt;!-- /wp:q {\"a\":1} -->");
        String noSpaceBeforeJson = "<!-- wp:p{} -->";
        assertThat(PreFilters.neutralizeBlockDelimiters(noSpaceBeforeJson, true))
                .isSameAs(noSpaceBeforeJson);
        assertThat(PreFilters.neutralizeBlockDelimiters(
                        "<!-- wp:a/b-c_1 /--><!--\n/wp:x \f--><!-- wp:p {\"a\":\"}\"} /-->", true))
                .isEqualTo("&lt;!-- wp:a/b-c_1 /-->&lt;!--\n/wp:x \f-->&lt;!-- wp:p {\"a\":\"}\"} /-->");
        assertThat(PreFilters.neutralizeBlockDelimiters("<!-- wp:p {\"a\":1}--><!-- wp:q {} -->", true))
                .isEqualTo("&lt;!-- wp:p {\"a\":1}-->&lt;!-- wp:q {} -->");
    }

    @Test
    void delimitersRepeatedFromBlocksLeftOpenAreNeutralized() {
        // Ruling #11 (README, difference #11). WordPress gives
        // <!-- wp:r --><!-- wp:q {"a":"x"} /-->t<!-- /wp:r --><!-- wp:p --> followed by the input's own r and q
        // delimiters, live, and <!-- /wp:p -->: the second q keeps its unfiltered script attribute.
        String out = WP.post("<!-- wp:p --><!-- wp:r --><!-- wp:q {\"a\":\"\\u003cscript\\u003ex\"} /-->t");
        assertThat(out)
                .isEqualTo("<!-- wp:r --><!-- wp:q {\"a\":\"x\"} /-->t<!-- /wp:r --><!-- wp:p -->"
                        + "&lt;!-- wp:r --&gt;&lt;!-- wp:q {\"a\":\"\\u003cscript\\u003ex\"} /--&gt;t<!-- /wp:p -->");
        assertNoUnfilteredAttributes(out);
        String raw = WP.post("<!-- wp:p --><!-- wp:r --><!-- wp:q {\"a\":\"<script>x\"} /-->t");
        assertThat(raw).doesNotContain("<!-- wp:q {\"a\":\"<script>x\"}");
        assertNoUnfilteredAttributes(raw);
    }

    @Test
    void delimitersAfterACloserThatClosesNothingAreNeutralized() {
        // Ruling #11 (README, difference #11). WordPress returns the input unchanged here: the block parser
        // keeps everything after the stray closer as classic content, so the q block is never filtered.
        String out =
                WP.post("a<!-- wp:p -->t<!-- /wp:p --><!-- /wp:x --><!-- wp:q {\"a\":\"\\u003cscript\\u003ex\"} /-->");
        assertThat(out)
                .isEqualTo("a<!-- wp:p -->t<!-- /wp:p --><!-- /wp:x -->"
                        + "&lt;!-- wp:q {\"a\":\"\\u003cscript\\u003ex\"} /--&gt;");
        assertNoUnfilteredAttributes(out);
    }

    @Test
    void commentsThatKsesRebuildsIntoDelimitersAreNeutralized() {
        // Ruling #11 (README, difference #11): WordPress leaves these delimiters live with "<script>x".
        assertThat(WP.post("<!--\nwp:p {\"a\":\"\\u003cscript\\u003ex\"} --->"))
                .isEqualTo("&lt;!--\nwp:p {\"a\":\"\\u003cscript\\u003ex\"} ---&gt;");
        assertThat(WP.post("<!-- wp:p {\"a\":\"\\u003cscript\\u003ex\",\"b\":\">\"} <x>-->"))
                .isEqualTo("&lt;!-- wp:p {\"a\":\"\\u003cscript\\u003ex\",\"b\":\"&gt;\"} -->");
        String lt = "\\u003c";
        String gt = "\\u003e";
        String a = "{\"a\":\"" + lt + "script" + gt + "x\"}";
        String ab = "{\"a\":\"" + lt + "script" + gt + "x\",\"b\":\">\"}";
        for (String in : new String[] {
            "<!--\nwp:p " + a + " --->",
            "<!-- wp:p " + a + " ---->",
            "<!-- wp:p " + a + " --->",
            "<!--\nwp:p " + a + " ---->",
            "<!--\nwp:p " + a + " -- -->",
            "<!-- wp:p<x> " + a + " -->",
            "<!-- wp:p<b> " + a + " -->",
            "<!-- wp:p <x>" + a + " -->",
            "<!-- <x>wp:p " + a + " -->",
            "<!--<x> wp:p " + a + " -->",
            "<!-- wp:p " + a + "<x> -->",
            "<!-- wp:p " + a + " <x>-->",
            "<!-- wp:p " + a + " --<x>>",
            "<!-- wp:p " + a + " &#45;->",
            "<!-- wp:p " + ab + " <x>-->",
            "<!-- wp:p " + ab + " <x>/-->",
            "<!-- wp:p<x> " + ab + " /-->",
            "<!-- wp:p " + ab + " --<x>>"
        }) {
            assertNoUnfilteredAttributes(WP.post(in));
            assertNoUnfilteredAttributes(WP.post(in + "<!-- wp:p -->x".repeat(1100)));
        }
    }

    @Test
    void openersWhoseCommentEndsInsideTheirJsonAreNeutralized() {
        // Ruling #11 (README, difference #11): the opener's comment token ends at the "-->" inside its JSON, and a
        // later comment, rebuilt by kses, supplies the attribute terminator. WordPress leaves a live block with
        // "<script>x" here.
        String json = "{\"a\":\"\\u003cscript\\u003ex\",\"b\":\"";
        assertThat(WP.post("<!-- wp:p " + json + "--><!--\"} --\n-->"))
                .isEqualTo("&lt;!-- wp:p " + json + "--><!--\"} \n-->");
        assertThat(WP.post("<!-- wp:p " + json + "--><!--a>\"} "))
                .isEqualTo("&lt;!-- wp:p " + json + "--><!--a&gt;\"} -->");
        for (String in : new String[] {
            "<!-- wp:p " + json + "-->y<!--\"} --\n-->z",
            "<!-- wp:p " + json + "--><!--\"} /--\n-->",
            "<!-- wp:p " + json + "--><!--\"}<x> -->",
            "<!-- wp:p " + json + "--><!--\"}\n--->",
            "<!-- wp:p " + json + "--><!--\"} --\n-->" + "<!-- wp:g -->".repeat(1100)
        }) {
            assertNoUnfilteredAttributes(WP.post(in));
        }
        assertThat(PreFilters.isCompleteBlockDelimiter("<!-- wp:p {\"a\":1} -->"))
                .isTrue();
        assertThat(PreFilters.isCompleteBlockDelimiter("<!-- /wp:p -->")).isTrue();
        assertThat(PreFilters.isCompleteBlockDelimiter("<!-- wp:p /-->")).isTrue();
        assertThat(PreFilters.isCompleteBlockDelimiter("<!-- wp:p {\"a\":\"-->"))
                .isFalse();
        assertThat(PreFilters.isCompleteBlockDelimiter("<!-- wp:p {\"a\":\"} -->\"} -->"))
                .isFalse();
        assertThat(PreFilters.isCompleteBlockDelimiter("<!-- wp:p x -->")).isFalse();
    }

    @Test
    void theFailSafeNeutralizesRepairedDelimiters() {
        // Ruling B1 (README, difference #10): the "--->" end is repaired before parsing, so the fail-safe path
        // neutralizes the repaired text, and treats extra dashes as an end.
        String hostile = "<!-- wp:p {\"a\":\"\\u003cscript\\u003ex\"} --->";
        for (String in :
                new String[] {hostile + "<!-- wp:p -->x".repeat(5000), hostile + "<!-- wp:g -->".repeat(1100)}) {
            assertThatThrownBy(() -> WpBlocks.parse(in)).isInstanceOf(BlockParseException.class);
            String out = bounded(in, () -> WP.post(in));
            assertThat(out).startsWith("&lt;!-- wp:p {\"a\":\"\\u003cscript\\u003ex\"} --&gt;");
            assertNoUnfilteredAttributes(out);
        }
        assertThat(PreFilters.neutralizeBlockDelimiters("<!-- wp:p ----><!-- wp:q {\"a\":1} /---->", true))
                .isEqualTo("&lt;!-- wp:p ---->&lt;!-- wp:q {\"a\":1} /---->");
    }

    @Test
    void closersInTheTextOfBlocksLeftOpenAreNeutralized() {
        // Ruling #11: a live closer there would close the wrong block when the output is parsed again.
        String out = WP.post("<!-- wp:p --><!-- wp:r --><!-- wp:s -->a<!-- /wp:s -->b");
        assertThat(out)
                .isEqualTo("<!-- wp:r --><!-- wp:s -->a<!-- /wp:s -->b<!-- /wp:r --><!-- wp:p -->"
                        + "&lt;!-- wp:r --&gt;&lt;!-- wp:s --&gt;a&lt;!-- /wp:s --&gt;b<!-- /wp:p -->");
        assertThat(WpBlocks.parse(out)).extracting(Block::name).containsExactly("core/r", "core/p");
    }

    /** Every attribute string of every block in {@code html} is already sanitized. */
    private static void assertNoUnfilteredAttributes(String html) {
        ArrayDeque<Block> pending = new ArrayDeque<>(WpBlocks.parse(html));
        while (!pending.isEmpty()) {
            Block block = pending.pop();
            pending.addAll(block.innerBlocks());
            if (!block.isFreeform()) {
                assertThat(block.attributes().toString()).as(block.toString()).doesNotContain("<");
            }
        }
    }

    @Test
    void blocksNestedBeyondTheParserLimitLoseTheirDelimiters() {
        // Expected value from ruling B1 (README, difference #10): WordPress's own output is quadratic here.
        String opener = "<!-- wp:group {\"a\":\"\\u003cscript\\u003ex\"} -->";
        String in = (opener + "<p onclick=\"x\">y</p>").repeat(2000) + "<!-- /wp:group -->".repeat(2000);
        assertThatThrownBy(() -> WpBlocks.parse(in)).isInstanceOf(BlockParseException.class);
        String out = bounded(in, () -> WP.post(in));
        assertThat(out)
                .isEqualTo(("&lt;" + opener.substring(1, opener.length() - 1) + "&gt;<p>y</p>").repeat(2000)
                        + "&lt;!-- /wp:group --&gt;".repeat(2000));
    }

    @Test
    void manyUnclosedBlocksLoseTheirDelimiters() {
        // Expected value from ruling B1 (README, difference #10)
        String in = "<!-- wp:p -->x".repeat(5000);
        assertThatThrownBy(() -> WpBlocks.parse(in)).isInstanceOf(BlockParseException.class);
        String out = bounded(in, () -> WP.post(in));
        assertThat(out).isEqualTo("&lt;!-- wp:p --&gt;x".repeat(5000));
    }

    @Test
    void unclosedBlocksThatWouldRepeatTooMuchTextLoseTheirDelimiters() {
        // Expected value from ruling B1 (README, difference #10): 1000 open blocks stay within the depth limit,
        // but recovering them would repeat more text than the parser allows.
        String text = "<b onclick=\"x\">y</b>";
        String in = ("<!-- wp:p {\"a\":\"\\u003cscript\\u003e\"} -->" + text).repeat(1000);
        assertThatThrownBy(() -> WpBlocks.parse(in)).isInstanceOf(BlockParseException.class);
        String out = bounded(in, () -> WP.post(in));
        assertThat(out).isEqualTo("&lt;!-- wp:p {\"a\":\"\\u003cscript\\u003e\"} --&gt;<b>y</b>".repeat(1000));
    }

    /** Runs {@code call} within 10 seconds and with at most 64 MiB plus 256 bytes per input character allocated. */
    private static String bounded(String in, Supplier<String> call) {
        return assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long id = Thread.currentThread().threadId();
            long before = threads.getThreadAllocatedBytes(id);
            String out = call.get();
            long allocated = threads.getThreadAllocatedBytes(id) - before;
            assertThat(allocated).isLessThan((64L << 20) + 256L * in.length());
            return out;
        });
    }
}
