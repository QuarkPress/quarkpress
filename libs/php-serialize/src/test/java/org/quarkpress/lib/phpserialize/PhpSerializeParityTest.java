package org.quarkpress.lib.phpserialize;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.DeltaShrinker;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.PhpTree;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * Compares this library with PHP 8.4 and WordPress 7.1.3 (testing/wp-oracle). Read cases run unserialize(),
 * serialize(), is_serialized() (strict and loose), is_serialized_string(), maybe_unserialize() and
 * maybe_serialize() on the same bytes; write cases build the same value on both sides and compare serialize()
 * and maybe_serialize(). Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class PhpSerializeParityTest {

    static final Path DRIVER = Path.of("src/test/php/serialize-driver.php");
    static final Path CASES = Path.of("src/test/resources/wp-parity-cases.json");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-skip.txt");
    static final Path CORPUS = Path.of("src/test/resources/wp");
    static final Path FAILURES = Path.of("target/wp-parity-failures.json");
    static final int MIN_COMPARISONS = 20_000;
    static final int GENERATED_VALUES = 2_500;
    static final int MUTATED_VALUES = 2_000;
    static final int RANDOM_DOUBLES = 1_000;
    static final int SHRUNK_CASES = 3;
    static final int SHRINK_ROUNDS = 12;
    private static final byte[] MUTATION_BYTES = "0123456789:;{}\"+-.EeiIsSaAOCrRbdN \n\0".getBytes(ISO_8859_1);

    private record ReadCase(String id, byte[] input, boolean generated) {}

    private record WriteCase(String id, ObjectNode tree) {}

    @Test
    void matchesPhpAndWordPress() throws Exception {
        Path wordpress = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), "wp-includes/functions.php");
        PhpOracle oracle = PhpOracle.create(wordpress);

        List<ReadCase> reads = new ArrayList<>();
        reads.addAll(handWrittenCases());
        reads.addAll(corpusCases());
        reads.addAll(generatedCases());
        List<WriteCase> writes = writeCases();

        List<PhpOracle.Case> batch = new ArrayList<>();
        for (ReadCase c : reads) {
            batch.add(new PhpOracle.Case(c.id(), readPayload(c.input())));
        }
        for (WriteCase c : writes) {
            batch.add(new PhpOracle.Case(c.id(), writePayload(c.tree())));
        }
        Map<String, JsonNode> results = oracle.run(DRIVER, batch);

        ParityRun run = new ParityRun("lib-php-serialize", ParitySkipList.load(SKIPS), FAILURES);
        Map<String, byte[]> generatedInputs = new LinkedHashMap<>();
        for (ReadCase c : reads) {
            if (c.generated()) {
                run.generated(c.id(), new String(c.input(), ISO_8859_1));
                generatedInputs.put(c.id(), c.input());
            }
            compareRead(run, c.id(), c.input(), results.get(c.id()));
        }
        for (WriteCase c : writes) {
            compareWrite(run, c.id(), c.tree(), results.get(c.id()));
        }
        for (String id : run.failedGeneratedCases().stream().limit(SHRUNK_CASES).toList()) {
            String input = new String(generatedInputs.get(id), ISO_8859_1);
            run.shrunk(id, DeltaShrinker.shrink(input, candidates -> stillDiffer(oracle, candidates), SHRINK_ROUNDS));
        }
        System.out.println("lib-php-serialize parity: " + reads.size() + " read cases, " + writes.size()
                + " write cases, " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
    }

    // ---- comparisons ----

    static void compareRead(ParityRun run, String id, byte[] input, JsonNode wp) {
        PhpValue parsed = parseOrNull(input);
        ObjectNode tree = parsed == null ? PhpTree.failed() : PhpTreeEncoder.encodeResolved(parsed);
        run.compare(id, "unserialize", wp.get("unserialize"), tree);

        boolean usable = parsed != null && !"cycle".equals(PhpTree.type(tree));
        JsonNode wpReserialized = wp.get("reserialize");
        // PHP renumbers back-references when it writes; byte aspects only hold for documents PHP writes unchanged
        boolean renumbered = parsed != null
                && PhpReferences.containsReference(parsed)
                && !wpReserialized.isNull()
                && !Arrays.equals(Base64.getDecoder().decode(wpReserialized.asText()), input);
        if (!renumbered) {
            PhpValue canonical = usable ? PhpTreeEncoder.withoutFloatLexemes(parsed) : null;
            run.compare(
                    id,
                    "reserialize",
                    bytesText(wpReserialized),
                    canonical == null ? null : text(PhpSerialize.serialize(canonical)));
            run.compare(
                    id,
                    "maybe_serialize_value",
                    bytesText(wp.get("maybe_serialize_value")),
                    canonical == null ? null : text(WordPressSerialization.maybeSerialize(canonical)));
        }
        String latin1 = text(input);
        run.compare(
                id,
                "is_serialized",
                wp.get("is_serialized").booleanValue(),
                WordPressSerialization.isSerialized(latin1));
        run.compare(
                id,
                "is_serialized_loose",
                wp.get("is_serialized_loose").booleanValue(),
                WordPressSerialization.isSerialized(latin1, false));
        run.compare(
                id,
                "is_serialized_string",
                wp.get("is_serialized_string").booleanValue(),
                WordPressSerialization.isSerializedString(latin1));
        run.compare(
                id,
                "maybe_unserialize",
                wp.get("maybe_unserialize"),
                PhpTreeEncoder.encodeResolved(WordPressSerialization.maybeUnserialize(input)));
        run.compare(
                id,
                "maybe_serialize_input",
                bytesText(wp.get("maybe_serialize_input")),
                text(WordPressSerialization.maybeSerialize(PhpString.ofBytes(input))));
    }

    static void compareWrite(ParityRun run, String id, ObjectNode tree, JsonNode wp) {
        PhpValue value = PhpTreeEncoder.decode(tree);
        run.compare(id, "serialize", bytesText(wp.get("serialize")), text(PhpSerialize.serialize(value)));
        run.compare(
                id,
                "maybe_serialize",
                bytesText(wp.get("maybe_serialize")),
                text(WordPressSerialization.maybeSerialize(value)));
    }

    /** One oracle run for a shrinking round: which candidates still differ from WordPress. */
    private static List<Boolean> stillDiffer(PhpOracle oracle, List<String> candidates) {
        try {
            List<PhpOracle.Case> batch = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                batch.add(new PhpOracle.Case(
                        "shrink-" + i, readPayload(candidates.get(i).getBytes(ISO_8859_1))));
            }
            Map<String, JsonNode> results = oracle.run(DRIVER, batch);
            List<Boolean> verdicts = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                ParityRun probe =
                        new ParityRun("shrink", ParitySkipList.empty(), Path.of("target/wp-parity-shrink.json"));
                compareRead(probe, "shrink-" + i, candidates.get(i).getBytes(ISO_8859_1), results.get("shrink-" + i));
                verdicts.add(!probe.mismatches().isEmpty());
            }
            return verdicts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- inputs ----

    private static List<ReadCase> handWrittenCases() throws IOException {
        List<ReadCase> cases = new ArrayList<>();
        for (JsonNode c : OracleJson.MAPPER.readTree(CASES.toFile())) {
            byte[] input = c.has("utf8")
                    ? c.get("utf8").asText().getBytes(UTF_8)
                    : c.get("latin1").asText().getBytes(ISO_8859_1);
            cases.add(new ReadCase(c.get("id").asText(), input, false));
        }
        String deep = "a:1:{i:0;".repeat(300) + "i:1;" + "}".repeat(300);
        cases.add(new ReadCase("audit-25-depth-300", deep.getBytes(ISO_8859_1), false));
        return cases;
    }

    private static List<ReadCase> corpusCases() throws IOException {
        try (Stream<Path> files = Files.list(CORPUS)) {
            List<ReadCase> cases = new ArrayList<>();
            for (Path p :
                    files.filter(f -> f.toString().endsWith(".ser")).sorted().toList()) {
                cases.add(new ReadCase("corpus/" + p.getFileName(), Files.readAllBytes(p), false));
            }
            return cases;
        }
    }

    private static List<ReadCase> generatedCases() {
        List<ReadCase> cases = new ArrayList<>();
        List<PhpValue> values = GeneratedCases.sample(PhpValueArbitraries.values(), "20261014", GENERATED_VALUES);
        for (int i = 0; i < values.size(); i++) {
            if (!PhpTreeEncoder.mentionsUnavailableClasses(values.get(i))) {
                cases.add(new ReadCase("gen/value-" + i, PhpSerialize.serialize(values.get(i)), true));
            }
        }
        List<PhpValue> toMutate = GeneratedCases.sample(PhpValueArbitraries.values(), "20261015", MUTATED_VALUES);
        Random random = new Random(20261014L);
        for (int i = 0; i < toMutate.size(); i++) {
            byte[] bytes = PhpSerialize.serialize(toMutate.get(i));
            bytes[random.nextInt(bytes.length)] = MUTATION_BYTES[random.nextInt(MUTATION_BYTES.length)];
            if (keepMutated(bytes)) {
                cases.add(new ReadCase("gen/mutated-" + i, bytes, true));
            }
        }
        return cases;
    }

    /** Drops mutations that only show the documented differences #14, #23 and #24. */
    private static boolean keepMutated(byte[] bytes) {
        PhpParser parser = new PhpParser(bytes, ParseOptions.PHP_COMPATIBLE);
        PhpValue value;
        try {
            value = parser.parseDocument();
        } catch (PhpSerializeException e) {
            return true;
        }
        return !PhpTreeEncoder.mentionsUnavailableClasses(value)
                && !(parser.sawDuplicateKey() && PhpReferences.containsReference(value));
    }

    private static List<WriteCase> writeCases() {
        List<WriteCase> cases = new ArrayList<>();
        double[] fixed = {
            0.1,
            1.0,
            100.0,
            1e7,
            1e14,
            1e15,
            1e16,
            1e17,
            1e22,
            1e23,
            1e25,
            1e100,
            1e-4,
            1e-5,
            1e-6,
            1.5e-5,
            0.000099,
            0.00012345,
            0.30000000000000004,
            1.0 / 3,
            2.5,
            123456789012345.6,
            1234567890123456.7,
            12345678901234567.0,
            99999999999999990.0,
            9007199254740993.0,
            5e-324,
            4.9e-322,
            2.2250738585072014e-308,
            Double.MAX_VALUE,
            -0.0,
            0.0,
            -1.5e-7,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        };
        for (int i = 0; i < fixed.length; i++) {
            cases.add(new WriteCase("write/float-" + i, PhpTree.floating(PhpTreeEncoder.floatText(fixed[i]))));
        }
        Random random = new Random(20261014L);
        for (int i = 0; i < RANDOM_DOUBLES; i++) {
            double d = i % 2 == 0
                    ? Double.longBitsToDouble(random.nextLong())
                    : random.nextDouble() * Math.pow(10, random.nextInt(40) - 20);
            cases.add(new WriteCase("write/random-float-" + i, PhpTree.floating(PhpTreeEncoder.floatText(d))));
        }
        cases.add(new WriteCase(
                "write/text-key-then-integer-key",
                PhpTree.array(List.of(
                        entry(PhpTree.string("5"), PhpTree.integer(1)),
                        entry(PhpTree.integer(5), PhpTree.integer(2))))));
        cases.add(new WriteCase(
                "write/append-after-negative",
                PhpTree.array(List.of(
                        entry(PhpTree.integer(-5), PhpTree.string("a")),
                        entry(PhpTree.append(), PhpTree.string("b"))))));
        cases.add(new WriteCase(
                "write/append-after-two-negatives",
                PhpTree.array(List.of(
                        entry(PhpTree.integer(-5), PhpTree.integer(1)),
                        entry(PhpTree.integer(-10), PhpTree.integer(2)),
                        entry(PhpTree.append(), PhpTree.integer(3))))));
        cases.add(new WriteCase(
                "write/append-after-string-key",
                PhpTree.array(List.of(
                        entry(PhpTree.string("x"), PhpTree.integer(1)), entry(PhpTree.append(), PhpTree.integer(2))))));
        String[] texts = {
            "-0", "05", "9223372036854775807", "9223372036854775808", "-9223372036854775808", " 5", "5 ", "+5"
        };
        List<PhpTree.Entry> textKeys = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            textKeys.add(entry(PhpTree.string(texts[i]), PhpTree.integer(i + 1)));
        }
        cases.add(new WriteCase("write/integer-text-keys", PhpTree.array(textKeys)));
        cases.add(new WriteCase(
                "write/object-integer-properties",
                PhpTree.object(
                        "stdClass",
                        List.of(
                                entry(PhpTree.integer(5), PhpTree.integer(1)),
                                entry(PhpTree.string("6"), PhpTree.integer(2))))));
        List<String> strings = List.of("a:1:{i:0;i:1;}", "i:5;", "N;", "i:5;xyz", "hello", "d:INF;", "s:3:\"abc\";");
        for (int i = 0; i < strings.size(); i++) {
            cases.add(new WriteCase("write/string-" + i, PhpTree.string(strings.get(i))));
        }
        cases.add(new WriteCase("write/int", PhpTree.integer(5)));
        cases.add(new WriteCase("write/true", PhpTree.bool(true)));
        cases.add(new WriteCase("write/false", PhpTree.bool(false)));
        cases.add(new WriteCase("write/null", PhpTree.nul()));
        return cases;
    }

    // ---- helpers ----

    private static ObjectNode readPayload(byte[] input) {
        ObjectNode in = OracleJson.MAPPER.createObjectNode();
        in.put("kind", "read");
        in.put("b64", Base64.getEncoder().encodeToString(input));
        return in;
    }

    private static ObjectNode writePayload(ObjectNode tree) {
        ObjectNode in = OracleJson.MAPPER.createObjectNode();
        in.put("kind", "write");
        in.set("value", tree);
        return in;
    }

    private static PhpTree.Entry entry(ObjectNode key, ObjectNode value) {
        return new PhpTree.Entry(key, value);
    }

    private static PhpValue parseOrNull(byte[] input) {
        try {
            return PhpSerialize.parse(input, ParseOptions.PHP_COMPATIBLE);
        } catch (PhpSerializeException e) {
            return null;
        }
    }

    /** PHP's base64 bytes as one char per byte, or null; readable in failure reports. */
    private static String bytesText(JsonNode base64OrNull) {
        return base64OrNull == null || base64OrNull.isNull()
                ? null
                : new String(Base64.getDecoder().decode(base64OrNull.asText()), ISO_8859_1);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, ISO_8859_1);
    }
}
