package org.quarkpress.blocks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.lib.wpblocks.Block;
import org.quarkpress.lib.wpblocks.WpBlocks;

class PatternResolverTest {

    private final Map<String, BlockPattern> registered = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private BlockPattern register(BlockPattern pattern) {
        registered.put(pattern.name(), pattern);
        return pattern;
    }

    private BlockPattern register(String name, String title, String content) {
        return register(BlockPattern.builder(name, title, content).build());
    }

    private PatternResolver resolver(int limit) {
        return new PatternResolver(name -> Optional.ofNullable(registered.get(name)), limit, warnings::add);
    }

    private static String ref(String slug) {
        return "<!-- wp:pattern {\"slug\":\"" + slug + "\"} /-->";
    }

    /** The blocks of the whole tree, in document order. */
    private static List<Block> everyBlock(String content) {
        List<Block> all = new ArrayList<>();
        List<Block> pending = new ArrayList<>(WpBlocks.parse(content));
        while (!pending.isEmpty()) {
            Block block = pending.remove(pending.size() - 1);
            all.add(block);
            pending.addAll(block.innerBlocks());
        }
        return all;
    }

    private static long count(String content, String name) {
        return everyBlock(content).stream()
                .filter(block -> name.equals(block.name()))
                .count();
    }

    @Test
    void resolvesLikeWordPress() {
        // the harness's patterns of b11, registered as WordPress's must-use plugin registers them
        BlockPattern inner = register(
                BlockPattern.builder("qp/inner", "Inner", "<!-- wp:paragraph --><p>Inner</p><!-- /wp:paragraph -->")
                        .categories(List.of("buttons", "columns", "query", "x"))
                        .build());
        BlockPattern outer = register(
                "qp/outer",
                "Outer",
                "<!-- wp:group --><div class=\"wp-block-group\">" + ref("qp/inner") + ref("qp/missing")
                        + ref("qp/outer") + "</div><!-- /wp:group -->");
        PatternResolver resolver = resolver(10000);

        assertThat(resolver.content(inner)).isEqualTo("<!-- wp:paragraph --><p>Inner</p><!-- /wp:paragraph -->");
        // WordPress's answer, recorded: the pattern listed is not on the chain, so its self reference is expanded
        // once; inside that copy the self reference is removed; unknown slugs stay; a single inserted block gets the
        // metadata of its pattern, categories as registered
        assertThat(resolver.content(outer))
                .isEqualTo("<!-- wp:group --><div class=\"wp-block-group\"><!-- wp:paragraph {\"metadata\":"
                        + "{\"patternName\":\"qp/inner\",\"name\":\"Inner\",\"categories\":[\"buttons\",\"columns\","
                        + "\"query\",\"x\"]}} --><p>Inner</p><!-- /wp:paragraph --><!-- wp:pattern"
                        + " {\"slug\":\"qp/missing\"} /--><!-- wp:group {\"metadata\":{\"patternName\":\"qp/outer\","
                        + "\"name\":\"Outer\"}} --><div class=\"wp-block-group\"><!-- wp:paragraph {\"metadata\":"
                        + "{\"patternName\":\"qp/inner\",\"name\":\"Inner\",\"categories\":[\"buttons\",\"columns\","
                        + "\"query\",\"x\"]}} --><p>Inner</p><!-- /wp:paragraph --><!-- wp:pattern"
                        + " {\"slug\":\"qp/missing\"} /--></div><!-- /wp:group --></div><!-- /wp:group -->");
        assertThat(warnings).isEmpty();
    }

    @Test
    void nestedContentIsTrimmedAndTheTopLevelIsNot() {
        register("p/two", "Two", "\n<!-- wp:a /-->\n<!-- wp:b /-->\n");
        BlockPattern top = register("p/top", "Top", " \n" + ref("p/two") + "\n" + ref("p/two") + "\t\n");
        // the listed pattern's own whitespace stays, as the recorded theme patterns keep their newlines (b08); the
        // inserted pattern is trimmed and has three top-level blocks (text between them is one), so no metadata
        assertThat(resolver(10000).content(top))
                .isEqualTo(" \n<!-- wp:a /-->\n<!-- wp:b /-->\n<!-- wp:a /-->\n<!-- wp:b /-->\t\n");
        BlockPattern spaced = register("p/spaced", "Spaced", "<!-- wp:a {\"k\" : 1} --><p>x</p><!-- /wp:a -->");
        assertThat(resolver(10000).content(spaced)).isEqualTo("<!-- wp:a {\"k\":1} --><p>x</p><!-- /wp:a -->");
    }

    @Test
    void cyclesAreRemoved() {
        // derived from b11's self-reference result, not recorded (ruling R1): a is not on the chain while a itself is
        // resolved, so b's copy holds a's blocks once, whose reference to b is removed
        BlockPattern a =
                register("p/a", "A", "<!-- wp:group --><div class=\"a\">" + ref("p/b") + "</div><!-- /wp:group -->");
        register("p/b", "B", "<!-- wp:group --><div class=\"b\">" + ref("p/a") + "</div><!-- /wp:group -->");

        String content = resolver(10000).content(a);

        assertThat(content)
                .isEqualTo("<!-- wp:group --><div class=\"a\"><!-- wp:group {\"metadata\":{\"patternName\":\"p/b\","
                        + "\"name\":\"B\"}} --><div class=\"b\"><!-- wp:group {\"metadata\":{\"patternName\":\"p/a\","
                        + "\"name\":\"A\"}} --><div class=\"a\"></div><!-- /wp:group --></div><!-- /wp:group -->"
                        + "</div><!-- /wp:group -->");
        assertThat(count(content, "core/pattern")).isZero();
    }

    @Test
    void theTextAroundARemovedReferenceStays() {
        BlockPattern self = register(
                "p/self",
                "Self",
                "<!-- wp:group --><div>x" + ref("p/self") + "y<!-- wp:a /-->z</div><!-- /wp:group -->");
        assertThat(resolver(10000).content(self))
                .isEqualTo("<!-- wp:group --><div>x<!-- wp:group {\"metadata\":{\"patternName\":\"p/self\","
                        + "\"name\":\"Self\"}} --><div>xy<!-- wp:a /-->z</div><!-- /wp:group -->y<!-- wp:a /-->z"
                        + "</div><!-- /wp:group -->");
    }

    @Test
    void metadataKeepsWhatThePatternLacksAndIsSanitized() {
        register(BlockPattern.builder(
                        "p/meta",
                        "Ti<b>tle</b>\n x",
                        "<!-- wp:a {\"metadata\":{\"name\":\"Old\",\"bindings\":{\"k\":1},\"description\":\"Kept\"}}"
                                + " /-->")
                .categories(List.of(" spaced\tcategory ", "<i>x</i>"))
                .build());
        register(BlockPattern.builder("p/described", "D", "<!-- wp:a /-->")
                .description("One\n<b>two</b>  three")
                .build());
        BlockPattern top = register("p/top", "Top", ref("p/meta") + ref("p/described"));

        // the names WordPress's array assignment keeps in place, the new ones after them (PHP's rule, derived)
        assertThat(resolver(10000).content(top))
                .isEqualTo("<!-- wp:a {\"metadata\":{\"name\":\"Title x\",\"bindings\":{\"k\":1},"
                        + "\"description\":\"Kept\",\"patternName\":\"p/meta\",\"categories\":[\"spaced category\","
                        + "\"x\"]}} /--><!-- wp:a {\"metadata\":{\"patternName\":\"p/described\",\"name\":\"D\","
                        + "\"description\":\"One two three\"}} /-->");
    }

    @Test
    void fallbackMetadataIsSanitizedAndFalsyTitlesSkipped() {
        // a pattern without description or categories: the block's own values are taken, cast to text and sanitized,
        // as WordPress passes whichever value it takes through its text sanitizer; a "0" title or description is
        // skipped, and the block's own value then stays as it is
        register(
                "p/bare",
                "0",
                "<!-- wp:a {\"metadata\":{\"description\":7,\"categories\":[\" c \",true]," + "\"name\":5}} /-->");
        register(BlockPattern.builder("p/zero", "Z", "<!-- wp:a /-->")
                .description("0")
                .build());
        BlockPattern top = register("p/top", "Top", ref("p/bare") + ref("p/zero"));

        assertThat(resolver(10000).content(top))
                .isEqualTo("<!-- wp:a {\"metadata\":{\"description\":\"7\",\"categories\":[\"c\",\"1\"],\"name\":5,"
                        + "\"patternName\":\"p/bare\"}} /--><!-- wp:a {\"metadata\":{\"patternName\":\"p/zero\","
                        + "\"name\":\"Z\"}} /-->");
    }

    @Test
    void fanOutStopsAtTheLimit() {
        register("p/b", "B", "<!-- wp:paragraph --><p>x</p><!-- /wp:paragraph -->".repeat(500));
        BlockPattern a = register("p/a", "A", ref("p/b").repeat(500));

        String content = resolver(1000).content(a);

        List<Block> top = WpBlocks.parse(content);
        assertThat(top).hasSize(1498);
        assertThat(top.subList(0, 1000)).allMatch(block -> "core/paragraph".equals(block.name()));
        assertThat(top.subList(1000, 1498)).allMatch(block -> "core/pattern".equals(block.name()));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("p/a").contains("1000");
    }

    @Test
    void exponentialFanOutIsBounded() {
        // each level doubles: 2^40 paragraphs if nothing stopped it
        for (int level = 0; level < 40; level++) {
            register("p/" + level, "L" + level, ref("p/" + (level + 1)) + ref("p/" + (level + 1)));
        }
        register("p/40", "L40", "<!-- wp:paragraph --><p>x</p><!-- /wp:paragraph -->");

        String content = resolver(10000).content(registered.get("p/0"));

        assertThat(count(content, "core/paragraph")).isPositive().isLessThanOrEqualTo(10000);
        assertThat(count(content, "core/pattern")).isPositive();
        assertThat(warnings).hasSize(1);
    }

    @Test
    void deepChainsDoNotOverflow() {
        for (int i = 1; i < 200; i++) {
            register("p/" + i, "P" + i, "<!-- wp:group --><div>" + ref("p/" + (i + 1)) + "</div><!-- /wp:group -->");
        }
        register("p/200", "P200", "<!-- wp:paragraph --><p>end</p><!-- /wp:paragraph -->");

        String content = resolver(10000).content(registered.get("p/1"));

        assertThat(count(content, "core/group")).isEqualTo(199);
        assertThat(count(content, "core/paragraph")).isEqualTo(1);
        assertThat(count(content, "core/pattern")).isZero();
        assertThat(warnings).isEmpty();
    }

    @Test
    void chainsDeeperThanTheParserAllowsStop() {
        for (int i = 1; i < 5000; i++) {
            register("p/" + i, "P" + i, "<!-- wp:group --><div>" + ref("p/" + (i + 1)) + "</div><!-- /wp:group -->");
        }
        register("p/5000", "P5000", "<!-- wp:paragraph --><p>end</p><!-- /wp:paragraph -->");

        String content = resolver(1_000_000).content(registered.get("p/1"));

        // the answer parses again (no deeper than the parser reads) and keeps the reference where it stopped
        assertThat(count(content, "core/pattern")).isEqualTo(1);
        assertThat(count(content, "core/paragraph")).isZero();
        assertThat(warnings).hasSize(1);
    }

    @Test
    void longChainsOfBareReferencesStop() {
        for (int i = 1; i < 5000; i++) {
            register("p/" + i, "P" + i, ref("p/" + (i + 1)));
        }
        register("p/5000", "P5000", "<!-- wp:paragraph --><p>end</p><!-- /wp:paragraph -->");

        String content = resolver(1_000_000).content(registered.get("p/1"));

        assertThat(WpBlocks.parse(content)).hasSize(1);
        assertThat(count(content, "core/pattern")).isEqualTo(1);
        assertThat(warnings).hasSize(1);
    }

    @Test
    void unparsableAttributesStay() {
        BlockPattern broken = register("p/broken", "Broken", "<!-- wp:pattern {\"slug\": -->");
        assertThat(resolver(10000).content(broken)).isEqualTo("<!-- wp:pattern {\"slug\": -->");
        // a slug PHP calls empty, one that is not text, and none at all stay where they are
        register("0", "Zero", "<!-- wp:a /-->");
        BlockPattern empty = register(
                "p/empty",
                "Empty",
                "<!-- wp:pattern /--><!-- wp:pattern {\"slug\":\"\"} /--><!-- wp:pattern {\"slug\":\"0\"} /-->"
                        + "<!-- wp:pattern {\"slug\":5} /--><!-- wp:pattern {\"slug\":} /-->");
        assertThat(resolver(10000).content(empty))
                .isEqualTo("<!-- wp:pattern /--><!-- wp:pattern {\"slug\":\"\"} /--><!-- wp:pattern"
                        + " {\"slug\":\"0\"} /--><!-- wp:pattern {\"slug\":5} /--><!-- wp:pattern /-->");
    }
}
