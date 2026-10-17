package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.testing.gen.Gens.elements;
import static org.quarkpress.testing.gen.Gens.frequency;
import static org.quarkpress.testing.gen.Gens.weighted;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.quarkpress.lib.wpblocks.Block;
import org.quarkpress.lib.wpblocks.WpBlocks;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Properties;

/**
 * Adversarial block documents in WordPress mode: the output always parses as blocks, and no block keeps an
 * attribute that still holds hostile markup (README, differences #10 and #11).
 */
class BlockFilterPropertyTest {

    private static final String LT = "\\u003c";
    private static final String GT = "\\u003e";

    private static final Gen<String> SPACE = elements(" ", " ", "\n", "\t", "\r", "\f", "\013", " \n ");
    private static final Gen<String> NAME = elements("p", "group", "core/x", "a/b-c", "template-part", "P", "x_1");
    private static final Gen<String> END =
            elements("-->", "-->", "-->", "--->", "---->", "-- -->", "--!>", "- ->", "&#45;->", "--<x>>");
    private static final Gen<String> INJECT =
            frequency(weighted(6, elements("")), weighted(1, elements("<x>", "<b>", "<script>", "<", ">", "<!-- -->")));
    private static final Gen<String> STRING = elements(
            "x",
            LT + "script" + GT + "x",
            "<script>x",
            "<b onclick=1>y</b>",
            LT + "b onclick=1" + GT + "y",
            LT + "img src=x onerror=y" + GT,
            LT + "x" + GT + "z",
            "}",
            " --> ",
            ">",
            "--",
            LT + "!-- wp:q {\\\"b\\\":\\\"" + LT + "script" + GT + "\\\"} /--" + GT);
    private static final Gen<String> KEY = elements("a", "tagName", LT + "x" + GT, "b" + LT + "script" + GT, "on");
    private static final Gen<String> ATTRIBUTES = random -> {
        if (random.nextInt(3) == 0) {
            return "";
        }
        StringBuilder json = new StringBuilder("{");
        int n = 1 + random.nextInt(3);
        for (int i = 0; i < n; i++) {
            json.append(i > 0 ? "," : "").append('"').append(KEY.next(random)).append("\":");
            json.append(
                    random.nextInt(4) == 0 ? "[\"" + STRING.next(random) + "\"]" : "\"" + STRING.next(random) + "\"");
        }
        return json.append('}').append(SPACE.next(random)).toString();
    };
    private static final Gen<String> OPENER = random -> "<!--" + SPACE.next(random) + INJECT.next(random) + "wp:"
            + NAME.next(random) + INJECT.next(random) + SPACE.next(random) + ATTRIBUTES.next(random)
            + INJECT.next(random) + END.next(random);
    private static final Gen<String> VOID = random -> "<!--" + SPACE.next(random) + "wp:" + NAME.next(random)
            + SPACE.next(random) + ATTRIBUTES.next(random) + INJECT.next(random) + "/" + END.next(random);
    private static final Gen<String> CLOSER = random -> "<!--" + SPACE.next(random) + "/wp:" + NAME.next(random)
            + SPACE.next(random) + (random.nextInt(4) == 0 ? ATTRIBUTES.next(random) : "")
            + (random.nextInt(5) == 0 ? "/" : "") + END.next(random);
    /** A tag inside a delimiter-like comment, after a '>' that keeps the less-than filter away: kses removes it. */
    private static final Gen<String> TAG_INSIDE = random -> "<!--" + SPACE.next(random) + "wp:" + NAME.next(random)
            + SPACE.next(random) + "{\"a\":\"" + STRING.next(random) + "\",\"b\":\">\"}" + SPACE.next(random)
            + elements("<x>", "<b>", "<script>", "<x><x>").next(random)
            + elements("-->", "/-->", "--->").next(random);

    /**
     * An opener whose comment token ends at a "-->" inside its JSON, then a comment that kses rebuilds into the
     * attribute terminator ('}', whitespace, an optional '/', "-->"), possibly at the end of the input.
     */
    private static final Gen<String> CROSS_TOKEN = random -> "<!--" + SPACE.next(random) + "wp:" + NAME.next(random)
            + SPACE.next(random) + "{\"a\":\"" + STRING.next(random) + "\",\"b\":\""
            + elements("-->", "--->", "x-->", "--\n-->").next(random) + "<!--"
            + elements("", "a>", "x", " ", "<x>", "<b>").next(random) + "\""
            + elements("} ", "}\n", "}\r\n", "} /", "}<x> ", "} <x>").next(random)
            + elements("-->", "--\n-->", "--->", "<x>-->", "-<x>->", "").next(random);

    private static final Gen<String> TEXT = elements(
            "x", "<p onclick=\"x\">y</p>", "<x>", "<!-- c -->", "<b>", "<", ">", "", "\n", "<!--", "-->", " } -->");
    private static final Gen<String> PIECE = frequency(
            weighted(4, OPENER),
            weighted(2, VOID),
            weighted(2, CLOSER),
            weighted(2, TEXT),
            weighted(1, TAG_INSIDE),
            weighted(1, CROSS_TOKEN));

    private static final Gen<String> SMALL = random -> {
        StringBuilder doc = new StringBuilder();
        int n = 1 + random.nextInt(12);
        for (int i = 0; i < n; i++) {
            doc.append(PIECE.next(random)).append(TEXT.next(random));
        }
        return doc.toString();
    };

    /** Beyond the block parser's limits, with a hostile delimiter in front: the fail-safe path. */
    private static final Gen<String> OVER_LIMIT = random -> {
        String hostile = OPENER.next(random) + VOID.next(random);
        return random.nextBoolean()
                ? hostile + "<!-- wp:p -->x".repeat(1100 + random.nextInt(100))
                : hostile + ("<!-- wp:p {\"a\":\"" + LT + "s" + GT + "\"} -->" + "y".repeat(60)).repeat(1000);
    };

    private static final Gen<String> DOCUMENTS = frequency(weighted(49, SMALL), weighted(1, OVER_LIMIT));

    @Test
    void outputNeverKeepsUnfilteredBlockAttributes() {
        Kses kses = Kses.wordPress();
        Properties.check("BlockFilterPropertyTest.outputNeverKeepsUnfilteredBlockAttributes", 3000, DOCUMENTS, doc -> {
            String out = kses.post(doc);
            ArrayDeque<Block> pending = new ArrayDeque<>(WpBlocks.parse(out));
            while (!pending.isEmpty()) {
                Block block = pending.pop();
                pending.addAll(block.innerBlocks());
                if (!block.isFreeform() && block.attributesValid()) {
                    assertClean(block.attributes(), block);
                }
            }
        });
    }

    @Test
    void generatedDocumentsCoverTheRiskyShapes() {
        // The generator must reach the shapes the property is about.
        List<String> docs = sample(400);
        assertThat(docs).anyMatch(d -> d.contains("--->"));
        assertThat(docs).anyMatch(d -> d.contains("<!--\nwp:"));
        assertThat(docs).anyMatch(d -> d.contains("\">\"} <x>-->"));
        assertThat(docs)
                .anyMatch(d -> d.startsWith("<!--") && d.substring(4).strip().startsWith("/wp:"));
        assertThat(docs).anyMatch(d -> d.length() > 10_000);
        assertThat(docs).anyMatch(d -> d.contains("\"b\":\"--><!--"));
    }

    private static List<String> sample(int n) {
        RandomGenerator random = Properties.random(Properties.seedOf("BlockFilterPropertyTest.coverage"));
        return Stream.generate(() -> DOCUMENTS.next(random)).limit(n).toList();
    }

    private static void assertClean(JsonNode value, Block block) {
        if (value.isTextual()) {
            assertCleanText(value.textValue(), block);
        }
        for (Iterator<String> names = value.fieldNames(); names.hasNext(); ) {
            assertCleanText(names.next(), block);
        }
        for (JsonNode child : value) {
            assertClean(child, block);
        }
    }

    private static void assertCleanText(String text, Block block) {
        String lower = text.toLowerCase(Locale.ROOT);
        assertThat(lower).as("attribute text of %s", block).doesNotContain("<script", "<x", "onclick", "onerror");
    }
}
