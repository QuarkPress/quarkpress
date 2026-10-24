package org.quarkpress.revisions.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.RevisionField;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * Compares the revisions module's pure rules with WordPress 7.1.3's functions (testing/wp-oracle): the limit and its
 * enabled rule for every constant value and with both filters, the revisioned fields with and without a filter, the
 * row data of a revision or autosave, whitespace normalization, the per-field change decision, and the revision
 * version of the reference's names (pinned against the reference, since QuarkPress stores no slug). Active only with
 * {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class RevisionsParityTest {

    static final Path DRIVER = Path.of("src/test/php/revisions-driver.php");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-revisions-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-revisions-failures.json");
    static final String[] WORDPRESS_FILES = {
        "wp-includes/functions.php", "wp-includes/formatting.php", "wp-includes/revision.php"
    };

    /** Half of the first green run's 1434 comparisons, rounded down to a multiple of 50. */
    static final int MIN_COMPARISONS = 700;

    static final Instant AT = Instant.parse("2026-01-15T12:00:00Z");

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    /** The constant values of the reference's table, with the integer an operator would configure for each. */
    static final Map<String, Integer> CONSTANTS =
            Map.of("true", -1, "false", 0, "0", 0, "5", 5, "-1", -1, "abc", 0, "2.9", 2);

    static final List<String> FIELD_KEYS = List.of("post_title", "post_content", "post_excerpt");

    static final Map<String, RevisionField> FIELDS = Map.of(
            "post_title",
            RevisionField.TITLE,
            "post_content",
            RevisionField.CONTENT,
            "post_excerpt",
            RevisionField.EXCERPT);

    static final List<String> FIXED_TEXTS = List.of(
            "a  b",
            "a\r\nb",
            "a\rb",
            "a\n\n\nb",
            "a \t  b",
            "a\n b",
            "a \nb",
            "  x\t\n ",
            cp(0x0B) + "x" + cp(0),
            "a" + cp(0xA0) + "b",
            "",
            " ",
            "\n\n",
            "a\r\n\r\nb",
            "tab\there",
            "x" + cp(0) + "y",
            cp(0x3000) + "a",
            "a" + cp(0x2028) + "b",
            "trailing \r",
            "\rleading");

    /** Generated text: words, ASCII whitespace of every kind, control characters and non-ASCII spaces. */
    static Gen<String> text() {
        Gen<String> pieces = Gens.elements(
                "a",
                "bc",
                " ",
                "  ",
                "\t",
                "\n",
                "\r",
                "\r\n",
                "\n\n",
                cp(0x0B),
                "\f",
                cp(0),
                cp(0xA0),
                cp(0x2003),
                cp(0x3000),
                cp(0xE9),
                "0",
                ".");
        return random -> {
            StringBuilder text = new StringBuilder();
            int count = random.nextInt(10);
            for (int i = 0; i < count; i++) {
                text.append(pieces.next(random));
            }
            return text.toString();
        };
    }

    static PostSnapshot post(String title, String content, String excerpt) {
        return new PostSnapshot(
                PostId.of(7),
                SiteId.of(1),
                "post",
                "publish",
                "s",
                title,
                content,
                excerpt,
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.of(AT),
                AT,
                AT,
                "open",
                "open",
                "",
                false,
                "g",
                Map.of(),
                1);
    }

    static RevisionSnapshot last(String title, String content, String excerpt) {
        return new RevisionSnapshot(
                RevisionId.of(8),
                SiteId.of(1),
                PostId.of(7),
                Optional.empty(),
                title,
                content,
                excerpt,
                Map.of(),
                false,
                AT);
    }

    @Test
    void matchesWordPress() throws Exception {
        Path sources = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(sources);
        ParityRun run = new ParityRun("revisions", ParitySkipList.load(SKIPS), FAILURES);

        compareKeep(oracle, run);
        compareFieldsDataAndVersion(oracle, run);
        compareWhitespaceAndChanges(oracle, run);

        System.out.println("revisions parity: " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
        assertThat(run.comparisons()).isGreaterThanOrEqualTo(MIN_COMPARISONS);
    }

    /** One process per constant value: keep and enabled for post, page and attachment, with and without filters. */
    private static void compareKeep(PhpOracle oracle, ParityRun run) throws Exception {
        ObjectNode supports = OracleJson.MAPPER.createObjectNode();
        supports.putArray("post").add("title").add("editor").add("revisions");
        supports.putArray("page").add("title").add("editor").add("revisions");
        supports.putArray("attachment").add("title");
        for (Map.Entry<String, Integer> constant : CONSTANTS.entrySet()) {
            List<PhpOracle.Case> cases = new ArrayList<>();
            for (String type : List.of("post", "page", "attachment")) {
                cases.add(new PhpOracle.Case("keep-" + type, keepCase(type, supports)));
                cases.add(new PhpOracle.Case(
                        "keep-generic-" + type, keepCase(type, supports).put("generic", 3)));
                cases.add(new PhpOracle.Case(
                        "keep-both-" + type,
                        keepCase(type, supports).put("generic", 3).put("typed", "5")));
                cases.add(new PhpOracle.Case(
                        "keep-typed-" + type, keepCase(type, supports).put("typed", "5")));
            }
            Map<String, JsonNode> out = oracle.withEnvironment("QP_WP_POST_REVISIONS", constant.getKey())
                    .run(DRIVER, cases);
            String id = "constant-" + constant.getKey();
            for (String type : List.of("post", "page", "attachment")) {
                boolean supported = !type.equals("attachment");
                int keep = RevisionRules.keep(constant.getValue(), supported);
                JsonNode plain = out.get("keep-" + type);
                run.compare(id + "-" + type, "keep", plain.get("keep").asInt(), keep);
                run.compare(id + "-" + type, "enabled", plain.get("enabled").asBoolean(), RevisionRules.enabled(keep));
                // with filters, WordPress's result is what a QuarkPress filter would return; the enabled rule is
                // QuarkPress's: a filter re-enables a type without support, and any negative number is unlimited
                run.compare(
                        id + "-generic-" + type,
                        "keep",
                        out.get("keep-generic-" + type).get("keep").asInt(),
                        3);
                run.compare(
                        id + "-generic-" + type,
                        "enabled",
                        out.get("keep-generic-" + type).get("enabled").asBoolean(),
                        RevisionRules.enabled(3));
                run.compare(
                        id + "-both-" + type,
                        "keep",
                        out.get("keep-both-" + type).get("keep").asInt(),
                        5);
                run.compare(
                        id + "-typed-" + type,
                        "keep",
                        out.get("keep-typed-" + type).get("keep").asInt(),
                        5);
                run.compare(
                        id + "-typed-" + type,
                        "enabled",
                        out.get("keep-typed-" + type).get("enabled").asBoolean(),
                        RevisionRules.enabled(5));
            }
        }
    }

    private static ObjectNode keepCase(String type, ObjectNode supports) {
        ObjectNode in = OracleJson.MAPPER.createObjectNode().put("op", "keep").put("type", type);
        in.set("supports", supports.deepCopy());
        return in;
    }

    private static void compareFieldsDataAndVersion(PhpOracle oracle, ParityRun run) throws Exception {
        List<PhpOracle.Case> cases = new ArrayList<>();
        cases.add(new PhpOracle.Case("fields-default", op("fields")));
        ObjectNode full = OracleJson.MAPPER
                .createObjectNode()
                .put("ID", 7)
                .put("post_title", "Hello")
                .put("post_content", "Body")
                .put("post_excerpt", "Ex")
                .put("post_author", 3)
                .put("post_modified", "2026-01-16 09:00:00")
                .put("post_modified_gmt", "2026-01-16 08:00:00");
        cases.add(new PhpOracle.Case("data-full", dataCase(full, false)));
        cases.add(new PhpOracle.Case("data-full-autosave", dataCase(full, true)));
        cases.add(new PhpOracle.Case(
                "data-content-only",
                dataCase(OracleJson.MAPPER.createObjectNode().put("ID", 7).put("post_content", "c"), true)));
        cases.add(new PhpOracle.Case(
                "data-title-only",
                dataCase(OracleJson.MAPPER.createObjectNode().put("ID", 7).put("post_title", "T"), true)));
        cases.add(new PhpOracle.Case(
                "data-empty", dataCase(OracleJson.MAPPER.createObjectNode().put("ID", 7), true)));
        for (String name : List.of("7-revision-v1", "7-autosave-v1", "7-revision", "7-revision-2", "text:x")) {
            cases.add(new PhpOracle.Case("version-" + name, op("version").put("name", name)));
        }
        Map<String, JsonNode> out = new LinkedHashMap<>(oracle.run(DRIVER, cases));
        // _wp_post_revision_fields keeps its filtered result in a static, so a filtered case changes every later
        // case of its process: each filter gets a process of its own (a chain of removals can share one)
        out.putAll(oracle.run(
                DRIVER,
                List.of(new PhpOracle.Case(
                        "fields-protected",
                        fieldsCase(
                                List.of("post_author", "post_name", "post_status", "ID", "post_date", "post_type"),
                                List.of())))));
        out.putAll(oracle.run(
                DRIVER,
                List.of(new PhpOracle.Case("fields-extra-menu", fieldsCase(List.of("menu_order"), List.of())))));
        out.putAll(oracle.run(
                DRIVER,
                List.of(new PhpOracle.Case("fields-extra-password", fieldsCase(List.of("post_password"), List.of())))));
        out.putAll(oracle.run(
                DRIVER,
                List.of(
                        new PhpOracle.Case("fields-no-excerpt", fieldsCase(List.of(), List.of("post_excerpt"))),
                        new PhpOracle.Case(
                                "fields-title-only", fieldsCase(List.of(), List.of("post_content", "post_excerpt"))),
                        new PhpOracle.Case("fields-none", fieldsCase(List.of(), FIELD_KEYS)))));

        run.compare("fields-default", "keys", keys(out, "fields-default"), "post_content,post_excerpt,post_title");
        run.compare("fields-protected", "keys", keys(out, "fields-protected"), "post_content,post_excerpt,post_title");
        run.compare(
                "fields-no-excerpt",
                "keys",
                keys(out, "fields-no-excerpt"),
                keys(EnumSet.of(RevisionField.TITLE, RevisionField.CONTENT)));
        run.compare("fields-title-only", "keys", keys(out, "fields-title-only"), keys(EnumSet.of(RevisionField.TITLE)));
        run.compare("fields-none", "keys", keys(out, "fields-none"), "");
        for (String id : List.of("fields-extra-menu", "fields-extra-password")) {
            // the three known fields match; the added column is a documented narrowing (skip-listed)
            run.compare(id, "keys", keysAmong(out, id), keys(RevisionField.all()));
            run.compare(id, "extra", extras(out, id), "");
        }
        // a revision's row data: every known field copied only when present, the others stored empty
        compareData(
                run,
                "data-full",
                out,
                AutosaveChanges.builder()
                        .title("Hello")
                        .content("Body")
                        .excerpt("Ex")
                        .build(),
                false);
        compareData(
                run,
                "data-full-autosave",
                out,
                AutosaveChanges.builder()
                        .title("Hello")
                        .content("Body")
                        .excerpt("Ex")
                        .build(),
                true);
        compareData(
                run,
                "data-content-only",
                out,
                AutosaveChanges.builder().content("c").build(),
                true);
        compareData(
                run,
                "data-title-only",
                out,
                AutosaveChanges.builder().title("T").build(),
                true);
        compareData(run, "data-empty", out, AutosaveChanges.builder().build(), true);
        run.compare(
                "data-full",
                "post_author",
                out.get("data-full").get("keys").toString().contains("post_author"),
                false);
        Map<String, String> versions = Map.of(
                "7-revision-v1", "1", "7-autosave-v1", "1", "7-revision", "0", "7-revision-2", "0", "text:x", "false");
        versions.forEach((name, expected) -> run.compare(
                "version-" + name,
                "version",
                out.get("version-" + name).get("version").asText(),
                expected));
    }

    private static void compareData(
            ParityRun run, String id, Map<String, JsonNode> out, AutosaveChanges changes, boolean autosave) {
        JsonNode data = out.get(id).get("data");
        for (Map.Entry<String, RevisionField> field : FIELDS.entrySet()) {
            String stored = data.has(field.getKey()) ? data.get(field.getKey()).asText() : "";
            run.compare(id, field.getKey(), stored, RevisionRules.autosaveField(changes, field.getValue()));
        }
        run.compare(id, "autosave", data.get("post_name").asText().contains("-autosave"), autosave);
        run.compare(id, "parent", data.get("post_parent").asInt(), 7);
        run.compare(id, "status", data.get("post_status").asText(), "inherit");
    }

    private static void compareWhitespaceAndChanges(PhpOracle oracle, ParityRun run) throws Exception {
        List<String> texts = new ArrayList<>(FIXED_TEXTS);
        texts.addAll(GeneratedCases.sample(text(), "20261022-revisions-whitespace", 600));
        List<String[]> pairs = new ArrayList<>(List.of(
                new String[] {"a  b", "a b"},
                new String[] {"a\r\nb", "a\nb"},
                new String[] {"a\n b", "a\nb"},
                new String[] {"a" + cp(0xA0) + "b", "a b"},
                new String[] {"same", "same"},
                new String[] {"", " "}));
        List<String> generatedPairs = GeneratedCases.sample(text(), "20261022-revisions-pairs", 300);
        for (int i = 0; i + 1 < generatedPairs.size(); i += 2) {
            pairs.add(new String[] {generatedPairs.get(i), generatedPairs.get(i + 1)});
        }
        List<PhpOracle.Case> cases = new ArrayList<>();
        List<PhpOracle.Case> removedCases = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            cases.add(new PhpOracle.Case("normalize-" + i, op("normalize").put("text", texts.get(i))));
        }
        for (int i = 0; i < pairs.size(); i++) {
            String[] pair = pairs.get(i);
            // the pair differs in one field at a time, so each field's decision is compared on its own
            for (String field : FIELD_KEYS) {
                cases.add(new PhpOracle.Case(
                        "changed-" + field + "-" + i, changedCase(field, pair[0], pair[1], List.of())));
            }
            removedCases.add(new PhpOracle.Case(
                    "changed-removed-" + i, changedCase("post_content", pair[0], pair[1], List.of("post_content"))));
        }
        Map<String, JsonNode> out = new LinkedHashMap<>(oracle.run(DRIVER, cases));
        // the removal filter is kept by _wp_post_revision_fields: its cases run in a process of their own
        out.putAll(oracle.run(DRIVER, removedCases));
        for (int i = 0; i < texts.size(); i++) {
            if (i >= FIXED_TEXTS.size()) {
                run.generated("normalize-" + i, OracleJson.MAPPER.writeValueAsString(texts.get(i)));
            }
            run.compare(
                    "normalize-" + i,
                    "normalize_whitespace",
                    out.get("normalize-" + i).get("value").asText(),
                    RevisionRules.normalizeWhitespace(texts.get(i)));
        }
        for (int i = 0; i < pairs.size(); i++) {
            String[] pair = pairs.get(i);
            for (String field : FIELD_KEYS) {
                String id = "changed-" + field + "-" + i;
                if (i >= 6) {
                    run.generated(id, OracleJson.MAPPER.writeValueAsString(List.of(pair[0], pair[1])));
                }
                run.compare(
                        id,
                        "changed",
                        out.get(id).get("changed").asBoolean(),
                        RevisionRules.fieldsChanged(
                                RevisionField.all(), lastWith(field, pair[0]), postWith(field, pair[1])));
            }
            run.compare(
                    "changed-removed-" + i,
                    "changed",
                    out.get("changed-removed-" + i).get("changed").asBoolean(),
                    RevisionRules.fieldsChanged(
                            EnumSet.of(RevisionField.TITLE, RevisionField.EXCERPT),
                            lastWith("post_content", pair[0]),
                            postWith("post_content", pair[1])));
        }
    }

    private static RevisionSnapshot lastWith(String field, String value) {
        return last(
                field.equals("post_title") ? value : "t",
                field.equals("post_content") ? value : "c",
                field.equals("post_excerpt") ? value : "e");
    }

    private static PostSnapshot postWith(String field, String value) {
        return post(
                field.equals("post_title") ? value : "t",
                field.equals("post_content") ? value : "c",
                field.equals("post_excerpt") ? value : "e");
    }

    private static ObjectNode op(String op) {
        return OracleJson.MAPPER.createObjectNode().put("op", op);
    }

    private static ObjectNode fieldsCase(List<String> add, List<String> remove) {
        ObjectNode in = op("fields");
        ArrayNode adds = in.putArray("add");
        add.forEach(adds::add);
        ArrayNode removes = in.putArray("remove");
        remove.forEach(removes::add);
        return in;
    }

    private static ObjectNode dataCase(ObjectNode post, boolean autosave) {
        ObjectNode in = op("data").put("autosave", autosave);
        in.set("post", post.deepCopy());
        return in;
    }

    private static ObjectNode changedCase(String field, String last, String post, List<String> remove) {
        ObjectNode in = op("changed");
        ObjectNode lastNode = in.putObject("last")
                .put("post_title", "t")
                .put("post_content", "c")
                .put("post_excerpt", "e");
        lastNode.put(field, last);
        ObjectNode postNode = in.putObject("post")
                .put("post_title", "t")
                .put("post_content", "c")
                .put("post_excerpt", "e");
        postNode.put(field, post);
        ArrayNode removes = in.putArray("remove");
        remove.forEach(removes::add);
        return in;
    }

    /** WordPress's keys, sorted and joined, so the order of a map is not compared. */
    private static String keys(Map<String, JsonNode> out, String id) {
        Set<String> keys = new TreeSet<>();
        out.get(id).get("keys").forEach(key -> keys.add(key.asText()));
        return String.join(",", keys);
    }

    private static String keysAmong(Map<String, JsonNode> out, String id) {
        Set<String> keys = new TreeSet<>();
        out.get(id).get("keys").forEach(key -> {
            if (FIELD_KEYS.contains(key.asText())) {
                keys.add(key.asText());
            }
        });
        return String.join(",", keys);
    }

    private static String extras(Map<String, JsonNode> out, String id) {
        Set<String> keys = new TreeSet<>();
        out.get(id).get("keys").forEach(key -> {
            if (!FIELD_KEYS.contains(key.asText())) {
                keys.add(key.asText());
            }
        });
        return String.join(",", keys);
    }

    private static String keys(Set<RevisionField> fields) {
        Set<String> keys = new TreeSet<>();
        FIELDS.forEach((key, field) -> {
            if (fields.contains(field)) {
                keys.add(key);
            }
        });
        return String.join(",", keys);
    }
}
