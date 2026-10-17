package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.PhpOracle;

/**
 * Compares lib-wxr with the WordPress Importer 0.9.6 (commit 3ca8839) and WordPress 7.1.3's export_wp()
 * (testing/wp-oracle). Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class WxrParityTest {

    static final Path FAILURES = Path.of("target/wp-parity-failures.json");
    static final int GENERATED_DOCUMENTS = 300;
    static final int MIN_COMPARISONS = 10_000;
    /** The aspect prefix of a value that differs only as {@link WxrParity#undecodedTailReference} explains. */
    static final String UNDECODED_TAIL_REFERENCE = "reader-undecoded-tail-reference:";

    @Test
    void matchesWordPress() throws Exception {
        PhpOracle oracle = WxrParity.oracle();

        List<WxrParity.FileCase> readerCases = new ArrayList<>(WxrParity.corpus());
        List<WxrArbitraries.Document> docs =
                GeneratedCases.sample(WxrArbitraries.documents(false), "20261017-reader", GENERATED_DOCUMENTS);
        for (int i = 0; i < docs.size(); i++) {
            readerCases.add(new WxrParity.FileCase("gen/doc-" + i, docs.get(i).write(), true));
        }
        Map<String, JsonNode> parsed = WxrParity.parse(oracle, readerCases, "reader");
        String parser = WxrParity.availableParser(parsed);
        Assumptions.assumeFalse(
                parser.equals("xml"),
                "PHP " + System.getProperty("wp.php", "php") + " has ext/xml without ext/simplexml or ext/dom, so the"
                        + " importer would use its expat parser, which is not compared; install ext/simplexml and"
                        + " ext/dom (CI) or neither");
        ParityRun run = new ParityRun("lib-wxr", WxrParity.skips(parser), FAILURES);

        compareReader(run, readerCases, parsed);

        System.out.println("lib-wxr parity (" + parser + "): " + readerCases.size() + " files read, "
                + run.comparisons() + " comparisons, " + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
    }

    /** Parity 1: the importer's view of each file against the library's reading of it (lenient mode). */
    static void compareReader(ParityRun run, List<WxrParity.FileCase> cases, Map<String, JsonNode> parsed) {
        for (WxrParity.FileCase c : cases) {
            JsonNode out = parsed.get(c.id());
            String used = out.get("parser").asText();
            boolean xmlProcessor = used.equals("xmlprocessor");
            boolean modified = xmlProcessor; // only that parser extracts post_modified (R51)
            if (c.generated()) {
                run.generated(c.id(), new String(c.bytes(), StandardCharsets.UTF_8));
            }
            TestWxr.Result read = TestWxr.read(c.bytes(), WxrReadOptions.LENIENT);
            String source = new String(c.bytes(), StandardCharsets.UTF_8);
            WxrParity.compare(
                    run,
                    c.id(),
                    "reader:",
                    ImporterView.fromImporter(out, modified),
                    ImporterView.fromReader(read, used, modified),
                    xmlProcessor ? (wp, qp) -> WxrParity.undecodedTailReference(wp, qp, source) : (wp, qp) -> false,
                    UNDECODED_TAIL_REFERENCE);
        }
    }

    /**
     * Where PHP has ext/simplexml (CI), the importer reads a well-formed export with its SimpleXML parser, so the
     * reader section above compares with that parser. Skipped on PHP without it, where the reader is compared
     * with the importer's XML-processor parser only.
     */
    @Test
    void simpleXmlParserReadsWellFormedExports() throws Exception {
        WxrParity.FileCase example =
                new WxrParity.FileCase("corpus/example-a.xml", TestWxr.resource("example-a.xml"), false);
        JsonNode out =
                WxrParity.parse(WxrParity.oracle(), List.of(example), "probe").get(example.id());
        String available = out.get("available").asText();
        Assumptions.assumeTrue(
                available.equals("simplexml"),
                "PHP " + System.getProperty("wp.php", "php") + " has no ext/simplexml (the importer would use its "
                        + available + " parser), so lib-wxr was compared with the importer's XML-processor parser only;"
                        + " the SimpleXML comparisons run where ext/simplexml, ext/dom and ext/xml are installed (CI)");
        assertThat(out.get("parser").asText()).isEqualTo("simplexml");
        assertThat(out.path("result").path("posts").isArray()).isTrue();
    }
}
