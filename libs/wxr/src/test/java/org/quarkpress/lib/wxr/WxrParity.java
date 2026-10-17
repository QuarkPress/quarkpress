package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.PinnedRepository;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/** Shared plumbing of the lib-wxr parity tests. */
final class WxrParity {

    static final Path IMPORTER_DRIVER = Path.of("src/test/php/importer-driver.php");
    static final Path WORK = Path.of("target/wxr-parity");
    static final String IMPORTER = "wordpress-importer-0.9.6";
    static final String[] WORDPRESS_FILES = {
        "wp-includes/functions.php",
        "wp-includes/formatting.php",
        "wp-includes/kses.php",
        "wp-includes/utf8.php",
        "wp-includes/compat-utf8.php",
        "wp-includes/general-template.php",
        "wp-includes/html-api/class-wp-html-span.php",
        "wp-admin/includes/export.php"
    };
    static final String ABSENT = "<absent>";

    record FileCase(String id, byte[] bytes, boolean generated) {}

    private WxrParity() {}

    static PhpOracle oracle() throws IOException, InterruptedException {
        Path wordpress = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PinnedRepository importer = PinnedRepository.load(IMPORTER);
        Path importerDir = WordPressSources.ensure(
                importer, importer.sha256ByPath().keySet().toArray(String[]::new));
        return PhpOracle.create(wordpress)
                .withEnvironment("QP_WXR_IMPORTER", importerDir.toAbsolutePath().toString());
    }

    /** The reference examples, the two real exports and the 25 edge cases. */
    static List<FileCase> corpus() throws IOException {
        List<FileCase> cases = new ArrayList<>();
        for (String name :
                List.of("example-a.xml", "example-b.xml", "example-c.xml", "export-full.xml", "export-quirks.xml")) {
            cases.add(new FileCase("corpus/" + name, TestWxr.resource(name), false));
        }
        try (Stream<Path> files = Files.list(Path.of("src/test/resources/wxr/cases"))) {
            for (Path p :
                    files.filter(f -> f.toString().endsWith(".xml")).sorted().toList()) {
                cases.add(new FileCase("corpus/cases/" + p.getFileName(), Files.readAllBytes(p), false));
            }
        }
        assertThat(cases).hasSize(30);
        return cases;
    }

    /** Writes the files under target/wxr-parity/{dir}/ and parses them all with the importer in one php run. */
    static Map<String, JsonNode> parse(PhpOracle oracle, List<FileCase> cases, String dir)
            throws IOException, InterruptedException {
        Path folder = WORK.resolve(dir);
        Files.createDirectories(folder);
        List<PhpOracle.Case> batch = new ArrayList<>();
        for (FileCase c : cases) {
            Path file = folder.resolve(c.id().replaceAll("[^A-Za-z0-9._-]", "_") + ".xml");
            Files.write(file, c.bytes());
            ObjectNode in = OracleJson.MAPPER
                    .createObjectNode()
                    .put("kind", "parse")
                    .put("file", file.toAbsolutePath().toString());
            batch.add(new PhpOracle.Case(c.id(), in));
        }
        return oracle.run(IMPORTER_DRIVER, batch);
    }

    /** "simplexml" (CI), "xml" (ext/xml without ext/simplexml or ext/dom) or "xmlprocessor" (neither). */
    static String availableParser(Map<String, JsonNode> results) {
        Set<String> available = new LinkedHashSet<>();
        results.values().forEach(r -> available.add(r.get("available").asText()));
        assertThat(available).hasSize(1);
        String parser = available.iterator().next();
        if (!parser.equals("simplexml") && !parser.equals("xml") && !parser.equals("xmlprocessor")) {
            throw new AssertionError("unknown importer parser " + parser);
        }
        return parser;
    }

    static ParitySkipList skips(String parser) throws IOException {
        return ParitySkipList.load(Path.of("src/test/resources/wp-parity-skip-" + parser + ".txt"));
    }

    /** Compares two normal forms leaf by leaf; the aspect is {@code prefix} + the leaf's path. */
    static void compare(ParityRun run, String id, String prefix, JsonNode wordpress, JsonNode quarkpress) {
        compare(run, id, prefix, wordpress, quarkpress, (wp, qp) -> false, "");
    }

    /**
     * As {@link #compare(ParityRun, String, String, JsonNode, JsonNode)}, except that a leaf whose two values
     * {@code known} explains is compared under the aspect {@code knownPrefix} + the leaf's path, so that one
     * skip-list entry can name a checked difference wherever it occurs and a report still names the field.
     */
    static void compare(
            ParityRun run,
            String id,
            String prefix,
            JsonNode wordpress,
            JsonNode quarkpress,
            BiPredicate<String, String> known,
            String knownPrefix) {
        Map<String, String> wp = flatten(wordpress);
        Map<String, String> qp = flatten(quarkpress);
        Set<String> paths = new LinkedHashSet<>(wp.keySet());
        paths.addAll(qp.keySet());
        for (String path : paths) {
            String w = wp.getOrDefault(path, ABSENT);
            String q = qp.getOrDefault(path, ABSENT);
            run.compare(id, (known.test(w, q) ? knownPrefix : prefix) + path, w, q);
        }
    }

    /**
     * True when the only difference is a named reference the importer's XML-processor parser left undecoded at
     * the end of a text: {@code wordpress} is {@code quarkpress} with one {@code &}, {@code <} or {@code >} written
     * back as {@code &amp;}, {@code &lt;} or {@code &gt;}, and that reference plus the text after it is at most 5
     * bytes of UTF-8. That parser's decoder only looks for a named reference whose {@code &} starts at least 6
     * bytes before the end of the text (seen on this host: {@code a]]&gt;b} stays as written, {@code a]]&gt;bc}
     * becomes {@code a]]>bc}); {@code &quot;} and {@code &apos;} are 6 bytes long and always decoded. The
     * undecoded tail must also appear in {@code source} (the file) right before an end tag, so that a value the
     * library decoded twice, or a reference written literally inside CDATA ({@code ]]></}), is not explained.
     */
    static boolean undecodedTailReference(String wordpress, String quarkpress, String source) {
        for (Map.Entry<String, String> ref : SHORT_REFERENCES.entrySet()) {
            int at = wordpress.lastIndexOf(ref.getKey());
            if (at >= 0
                    && wordpress.substring(at).getBytes(StandardCharsets.UTF_8).length <= 5
                    && source.contains(wordpress.substring(at) + "</")
                    && quarkpress.equals(wordpress.substring(0, at)
                            + ref.getValue()
                            + wordpress.substring(at + ref.getKey().length()))) {
                return true;
            }
        }
        return false;
    }

    private static final Map<String, String> SHORT_REFERENCES = Map.of("&amp;", "&", "&lt;", "<", "&gt;", ">");

    static Map<String, String> flatten(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        flatten(node, "", out);
        return out;
    }

    private static void flatten(JsonNode node, String path, Map<String, String> out) {
        if (node.isObject()) {
            if (node.isEmpty()) {
                out.put(path, "{}");
            }
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                flatten(e.getValue(), path + "/" + e.getKey(), out);
            }
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.put(path, "[]");
            }
            for (int i = 0; i < node.size(); i++) {
                flatten(node.get(i), path + "/" + i, out);
            }
        } else {
            out.put(path, node.asText());
        }
    }
}
