package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.OracleJson;
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
    static final int MIN_COMPARISONS = 15_000;
    /** Floors per section, so that neither the reader nor the writer comparisons can vanish behind the other. */
    static final int MIN_READER_COMPARISONS = 10_000;

    static final int MIN_WRITER_COMPARISONS = 5_000;
    static final int MIN_DATASETS = 65;
    /** The aspect prefix of a value that differs only as {@link WxrParity#undecodedTailReference} explains. */
    static final String UNDECODED_TAIL_REFERENCE = "reader-undecoded-tail-reference:";

    static final Path EXPORT_DRIVER = Path.of("src/test/php/export-driver.php");
    static final Path CASES = Path.of("src/test/resources/wp-parity-cases.json");
    static final int GENERATED_DATASETS = 60;
    /** Run-time values export_wp() writes (R5, R6). */
    static final List<String> RUN_TIME = List.of("/rss/channel[1]/pubDate[1]", "/rss/channel[1]/generator[1]");

    record Dataset(String id, JsonNode dataset, boolean generated) {}

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
        int readerComparisons = run.comparisons();
        List<Dataset> datasets = datasets();
        Map<String, byte[]> exports = compareWriter(run, oracle, datasets);
        int writerComparisons = run.comparisons() - readerComparisons;

        System.out.println("lib-wxr parity (" + parser + "): " + readerCases.size() + " files read ("
                + readerComparisons + " comparisons), " + datasets.size() + " datasets exported ("
                + writerComparisons + " comparisons), " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        assertThat(datasets).hasSizeGreaterThanOrEqualTo(MIN_DATASETS);
        assertThat(exports).hasSize(datasets.size());
        assertThat(readerComparisons).as("reader comparisons").isGreaterThanOrEqualTo(MIN_READER_COMPARISONS);
        assertThat(writerComparisons).as("writer comparisons").isGreaterThanOrEqualTo(MIN_WRITER_COMPARISONS);
        run.assertParity(MIN_COMPARISONS);
    }

    static List<Dataset> datasets() throws Exception {
        List<Dataset> datasets = new ArrayList<>();
        for (JsonNode c : OracleJson.MAPPER.readTree(CASES.toFile())) {
            datasets.add(new Dataset(c.get("id").asText(), c.get("dataset"), false));
        }
        List<ObjectNode> generated =
                GeneratedCases.sample(WxrArbitraries.exportDatasets(), "20261017-writer", GENERATED_DATASETS);
        for (int i = 0; i < generated.size(); i++) {
            datasets.add(new Dataset("gen/dataset-" + i, generated.get(i), true));
        }
        return datasets;
    }

    /** Parity 2: export_wp() and WxrWriter on the same datasets; returns WordPress's files by case id. */
    static Map<String, byte[]> compareWriter(ParityRun run, PhpOracle oracle, List<Dataset> datasets) throws Exception {
        List<PhpOracle.Case> batch = new ArrayList<>();
        for (Dataset ds : datasets) {
            batch.add(new PhpOracle.Case(
                    ds.id(), OracleJson.MAPPER.createObjectNode().set("dataset", ds.dataset())));
        }
        Map<String, JsonNode> exported = oracle.run(EXPORT_DRIVER, batch);
        Map<String, byte[]> wordpressFiles = new HashMap<>();
        for (Dataset ds : datasets) {
            byte[] wordpress =
                    Base64.getDecoder().decode(exported.get(ds.id()).get("b64").asText());
            wordpressFiles.put(ds.id(), wordpress);
            if (ds.generated()) {
                run.generated(ds.id(), ds.dataset().toString());
            }
            Map<String, String> wp = XmlTree.flatten(XmlTree.parse(wordpress));
            byte[] written = ExportDataset.write(ds.dataset());
            assertWellFormed(ds.id(), written);
            Map<String, String> qp = XmlTree.flatten(XmlTree.parse(written));
            RUN_TIME.forEach(path -> {
                wp.remove(path);
                qp.remove(path);
            });
            Set<String> paths = new LinkedHashSet<>(wp.keySet());
            paths.addAll(qp.keySet());
            for (String path : paths) {
                run.compare(
                        ds.id(),
                        "writer:" + path,
                        wp.getOrDefault(path, WxrParity.ABSENT),
                        qp.getOrDefault(path, WxrParity.ABSENT));
            }
        }
        return wordpressFiles;
    }

    /**
     * Reads the writer's file to the end with the JDK's plain StAX parser (no repair, no DTD), so that a bare
     * {@code &} or an XML-illegal character the writer let through cannot hide behind {@link XmlTree}'s lenient
     * input.
     */
    static void assertWellFormed(String id, byte[] xml) {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try {
            XMLStreamReader in = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            while (in.hasNext()) {
                in.next();
            }
            in.close();
        } catch (XMLStreamException e) {
            throw new AssertionError("WxrWriter wrote a malformed file for " + id + ": " + e.getMessage(), e);
        }
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
