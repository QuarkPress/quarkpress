package org.quarkpress.taxonomy.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.posts.runtime.DefaultSlugSanitizer;
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
 * Compares the taxonomy module's pure rules with WordPress 7.1.3's functions (testing/wp-oracle): taxonomy
 * registration (flags, capability names, labels), the save filters of term names, descriptions and slugs, the slug a
 * name generates, sanitize_text_field, ASCII lower-casing and the order normalization of term queries. Active only
 * with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class TaxonomyParityTest {

    static final Path DRIVER = Path.of("src/test/php/taxonomy-driver.php");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-taxonomy-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-taxonomy-failures.json");
    static final String[] WORDPRESS_FILES = {
        "wp-includes/formatting.php",
        "wp-includes/functions.php",
        "wp-includes/post.php",
        "wp-includes/taxonomy.php",
        "wp-includes/class-wp-taxonomy.php",
        "wp-includes/class-wp-term-query.php",
        "wp-includes/blocks.php",
        "wp-includes/class-wp-block-parser.php",
        "wp-includes/class-wp-block-parser-block.php",
        "wp-includes/class-wp-block-parser-frame.php",
        "wp-includes/compat.php",
        "wp-includes/kses.php",
        "wp-includes/class-wp-token-map.php",
        "wp-includes/html-api/class-wp-html-attribute-token.php",
        "wp-includes/html-api/class-wp-html-span.php",
        "wp-includes/html-api/class-wp-html-text-replacement.php",
        "wp-includes/html-api/class-wp-html-decoder.php",
        "wp-includes/html-api/class-wp-html-doctype-info.php",
        "wp-includes/html-api/class-wp-html-unsupported-exception.php",
        "wp-includes/html-api/class-wp-html-tag-processor.php",
        "wp-includes/html-api/html5-named-character-references.php"
    };

    /** The built-in taxonomies WordPress registers that QuarkPress registers too, in registration order. */
    static final List<String> BUILT_IN_KEYS = List.of("category", "post_tag");

    /** Half of the first green run's 7780 comparisons, rounded down to a multiple of 50. */
    static final int MIN_COMPARISONS = 3850;

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    /** Texts get_term_by casts to a term id: signs, whitespace, fractions, exponents, overflow and trailing text. */
    static final List<String> INT_TEXTS = List.of(
            "5",
            " 5",
            "\t\n\r\u000B\f12 ",
            "5abc",
            "0",
            "",
            "-5",
            "+7",
            "007",
            "1e3",
            "1E+2x",
            "2e-1",
            "1.9",
            ".5e1",
            "-.5",
            "5.",
            "5.e2",
            "5e",
            "5e+",
            "0x1A",
            "abc",
            "\u0663",
            "\u00A05",
            "9223372036854775807",
            "9223372036854775808",
            "-9223372036854775808",
            "-9223372036854775809",
            "99999999999999999999",
            "1e400",
            "-1e400",
            "9.9e18",
            "1e18");

    /** The behaviour reference's examples and the edge cases of each filter step. */
    static final List<String> FIXED_TEXTS = List.of(
            "  Rock & Roll  ",
            "a < b",
            "<b>Bold</b> name",
            "<em>Hi</em> & bye",
            "Tom's \"best\"",
            "x &amp; y",
            "news\tline\nbreak",
            "%41%42 octets",
            "a\\b",
            "a\\\\b",
            "<script>alert(1)</script>",
            "Caf" + cp(0xE9),
            "0",
            "%%4141x",
            "a <\nb",
            "News!",
            cp(0xC7) + "a va?",
            cp(0x65E5, 0x672C, 0x8A9E) + " " + cp(0x30BF, 0x30B0),
            cp(0xE9) + "moji " + cp(0x1F600),
            "100%",
            "a/b",
            "!!!",
            "&apos; &hellip; &bogus;",
            "<p>Hi <a href=\"http://x\" rel=\"nofollow\" target=\"_blank\" onclick=\"x\">l</a>"
                    + " <strong>s</strong><em>e</em><img src=x></p>",
            "<script>x</script><b>b</b> <a href=\"javascript:alert(1)\">j</a> <p class='c'>p</p> &copy;",
            "  spaced  ",
            "<",
            "<3 you",
            ">",
            "a\r\nb",
            "%e9t%C3%A9",
            "CAF" + cp(0xC9),
            cp(0x130) + "I",
            "",
            "a" + cp(0xA0) + "b " + cp(0x2013) + " c " + cp(0xD7) + " d",
            "&nbsp;x&ndash;y",
            "%4%411",
            "x %%%414141 y",
            "%%%%41414141",
            "%41%%4141%41 z",
            "a%4",
            "%G1%4a%%4A4a",
            "  %20 %2 0 %%2020  ");

    /**
     * Generated text of one to eight pieces: words, markup, entities, whitespace, control characters, percent octets,
     * quotes, backslashes, non-ASCII letters and the characters the slug sanitizer turns into '-' or 'x'. Never a block
     * delimiter (lib-wp-kses rewrites some block attributes, a library difference tested by the posts module).
     */
    static Gen<String> text() {
        Gen<String> pieces = Gens.elements(
                "Rock",
                "roll",
                " ",
                "  ",
                "\t",
                "\n",
                "&",
                "&amp;",
                "&copy;",
                "&bogus;",
                "&#38;",
                "&apos;",
                "<",
                ">",
                "<b>",
                "</b>",
                "<em>x</em>",
                "<script>s</script>",
                "<a href=\"javascript:x\">j</a>",
                "%41",
                "%zz",
                "%",
                "'",
                "\"",
                "\\",
                "-",
                "_",
                ".",
                "!",
                "?",
                "/",
                "0",
                "42",
                cp(0xE9),
                cp(0xC9),
                cp(0x65E5),
                cp(0x1F600),
                cp(0x130),
                "NEWS",
                String.valueOf((char) 0),
                String.valueOf((char) 0x0B),
                "\r",
                cp(0xA0),
                cp(0x2013),
                cp(0x2014),
                cp(0xD7),
                "&nbsp;",
                "&ndash;");
        return random -> {
            StringBuilder text = new StringBuilder();
            int count = 1 + random.nextInt(8);
            for (int i = 0; i < count; i++) {
                text.append(pieces.next(random));
            }
            return text.toString();
        };
    }

    /**
     * Generated directions: "asc" or "desc" in random letter case, sometimes with a space before or after, and
     * sometimes with a letter replaced by a non-ASCII stand-in (U+017F long s for an s, which Unicode case folding
     * maps to s; U+212A Kelvin sign for any other letter, which folds only to k), so that about a fifth of them are
     * ascending.
     */
    static Gen<String> order() {
        return random -> {
            String word = random.nextBoolean() ? "asc" : "desc";
            StringBuilder order = new StringBuilder();
            if (random.nextInt(5) == 0) {
                order.append(' ');
            }
            for (int i = 0; i < word.length(); i++) {
                char letter = word.charAt(i);
                if (random.nextInt(8) == 0) {
                    order.append(letter == 's' ? cp(0x17F) : cp(0x212A));
                } else {
                    order.append(random.nextBoolean() ? Character.toUpperCase(letter) : letter);
                }
            }
            if (random.nextInt(5) == 0) {
                order.append(' ');
            }
            return order.toString();
        };
    }

    /** Hand-built registration arguments: each line of WordPress's defaults and derivations. */
    static List<ObjectNode> fixedArgs() {
        List<ObjectNode> args = new ArrayList<>();
        args.add(OracleJson.MAPPER.createObjectNode());
        args.add(OracleJson.MAPPER.createObjectNode().put("public", false));
        args.add(OracleJson.MAPPER.createObjectNode().put("show_ui", false).put("show_in_menu", true));
        args.add(OracleJson.MAPPER.createObjectNode().put("hierarchical", true));
        ObjectNode genres = OracleJson.MAPPER.createObjectNode().put("hierarchical", true);
        genres.putObject("labels").put("name", "Genres");
        args.add(genres);
        ObjectNode legacy = OracleJson.MAPPER.createObjectNode().put("label", "Moods");
        legacy.putObject("labels").put("name", "").put("template_name", "Mood board");
        args.add(legacy);
        ObjectNode caps = OracleJson.MAPPER.createObjectNode();
        caps.putObject("capabilities").put("edit_terms", "edit_genres").put("assign_terms", "assign_genres");
        args.add(caps);
        args.add(OracleJson.MAPPER
                .createObjectNode()
                .put("show_in_rest", true)
                .put("rest_base", "genres")
                .put("default_term", "General")
                .put("sort", true));
        ObjectNode full = OracleJson.MAPPER.createObjectNode().put("show_in_rest", true);
        full.putObject("default_term").put("name", "General").put("slug", "gen").put("description", "All");
        args.add(full);
        args.add(OracleJson.MAPPER.createObjectNode().put("default_term", "0"));
        args.add(OracleJson.MAPPER.createObjectNode().put("default_term", ""));
        args.add(OracleJson.MAPPER
                .createObjectNode()
                .put("public", true)
                .put("publicly_queryable", false)
                .put("show_in_nav_menus", false)
                .put("show_tagcloud", false)
                .put("show_in_quick_edit", true)
                .put("show_admin_column", true));
        return args;
    }

    /** Generated registration arguments: flags given or not, labels given or not, capability names overridden. */
    static Gen<ObjectNode> generatedArgs() {
        String[] flags = {
            "public",
            "publicly_queryable",
            "hierarchical",
            "show_ui",
            "show_in_menu",
            "show_in_nav_menus",
            "show_tagcloud",
            "show_in_quick_edit",
            "show_admin_column",
            "show_in_rest",
            "sort"
        };
        String[] labelKeys = {
            "name",
            "singular_name",
            "menu_name",
            "all_items",
            "edit_item",
            "name_admin_bar",
            "archives",
            "template_name",
            "popular_items",
            "parent_item",
            "most_used"
        };
        return random -> {
            ObjectNode args = OracleJson.MAPPER.createObjectNode();
            for (String flag : flags) {
                if (random.nextInt(3) == 0) {
                    args.put(flag, random.nextBoolean());
                }
            }
            if (random.nextBoolean()) {
                ObjectNode labels = args.putObject("labels");
                for (String key : labelKeys) {
                    if (random.nextInt(4) == 0) {
                        labels.put(key, "L" + key.length());
                    }
                }
            }
            if (random.nextInt(4) == 0) {
                args.put("label", "Legacy");
            }
            if (random.nextInt(3) == 0) {
                ObjectNode caps = args.putObject("capabilities");
                for (String name : TaxonomyDefinition.CAPABILITY_KEYS) {
                    if (random.nextBoolean()) {
                        caps.put(name, "cap_" + name);
                    }
                }
            }
            if (random.nextInt(4) == 0) {
                args.put("default_term", "Default " + random.nextInt(10));
            }
            if (random.nextInt(4) == 0) {
                args.put("rest_base", "base" + random.nextInt(10));
            }
            return args;
        };
    }

    /**
     * The arguments create_initial_taxonomies passes for a built-in (labels not given; rewrite and query vars unused).
     */
    static ObjectNode builtInArgs(String key) {
        ObjectNode args = OracleJson.MAPPER
                .createObjectNode()
                .put("hierarchical", key.equals("category"))
                .put("public", true)
                .put("show_ui", true)
                .put("show_admin_column", true)
                .put("_builtin", true)
                .put("show_in_rest", true)
                .put("rest_base", key.equals("category") ? "categories" : "tags");
        String plural = key.equals("category") ? "categories" : "post_tags";
        args.putObject("capabilities")
                .put("manage_terms", key.equals("category") ? "manage_categories" : "manage_post_tags")
                .put("edit_terms", "edit_" + plural)
                .put("delete_terms", "delete_" + plural)
                .put("assign_terms", "assign_" + plural);
        return args;
    }

    @Test
    void matchesWordPress() throws Exception {
        Path sources = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(sources);
        ParityRun run = new ParityRun("taxonomy", ParitySkipList.load(SKIPS), FAILURES);
        TermSlugs slugs = new TermSlugs(new DefaultSlugSanitizer());

        List<String> texts = new ArrayList<>(FIXED_TEXTS);
        texts.addAll(GeneratedCases.sample(text(), "20261021-taxonomy-text", 600));
        List<ObjectNode> registrations = new ArrayList<>(fixedArgs());
        registrations.addAll(GeneratedCases.sample(generatedArgs(), "20261021-taxonomy-args", 150));
        List<String> orders = new ArrayList<>(List.of("ASC", "asc", "aSc", "DESC", "desc", "", "up", "ASC ", " asc"));
        orders.addAll(GeneratedCases.sample(order(), "20261021-taxonomy-order", 100));

        List<PhpOracle.Case> cases = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            for (String field : List.of("name", "description", "slug")) {
                cases.add(new PhpOracle.Case(
                        field + "-" + i,
                        OracleJson.MAPPER
                                .createObjectNode()
                                .put("op", "term_field")
                                .put("field", field)
                                .put("taxonomy", "category")
                                .put("text", text)));
            }
            cases.add(new PhpOracle.Case("generated-" + i, op("name_and_slug", "text", text)));
            cases.add(new PhpOracle.Case("text-" + i, op("text_field", "text", text)));
            cases.add(new PhpOracle.Case("lower-" + i, op("strtolower", "text", text)));
            cases.add(new PhpOracle.Case("int-" + i, op("int_cast", "text", text)));
        }
        for (int i = 0; i < INT_TEXTS.size(); i++) {
            cases.add(new PhpOracle.Case("int-fixed-" + i, op("int_cast", "text", INT_TEXTS.get(i))));
        }
        for (int i = 0; i < registrations.size(); i++) {
            cases.add(new PhpOracle.Case(
                    "taxonomy-" + i, registration("mood" + i, List.of("post", "post", "page"), registrations.get(i))));
        }
        for (String key : BUILT_IN_KEYS) {
            cases.add(new PhpOracle.Case("builtin-" + key, registration(key, List.of("post"), builtInArgs(key))));
        }
        for (int i = 0; i < orders.size(); i++) {
            cases.add(new PhpOracle.Case("order-" + i, op("parse_order", "order", orders.get(i))));
        }
        Map<String, JsonNode> out = oracle.run(DRIVER, cases);

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (i >= FIXED_TEXTS.size()) {
                String input = OracleJson.MAPPER.writeValueAsString(text);
                for (String prefix :
                        List.of("name-", "description-", "slug-", "generated-", "text-", "lower-", "int-")) {
                    run.generated(prefix + i, input);
                }
            }
            run.compare("name-" + i, "pre_term_name", value(out, "name-" + i), TermText.name(text));
            run.compare(
                    "description-" + i,
                    "pre_term_description",
                    value(out, "description-" + i),
                    TermText.description(text));
            run.compare(
                    "slug-" + i,
                    "pre_term_slug",
                    value(out, "slug-" + i),
                    slugs.sanitize(SiteId.of(1), "category", text));
            JsonNode generated = out.get("generated-" + i);
            String name = TermText.name(text);
            run.compare("generated-" + i, "name", generated.get("name").asText(), name);
            run.compare(
                    "generated-" + i,
                    "slug",
                    generated.get("slug").asText(),
                    slugs.generate(SiteId.of(1), "post_tag", name));
            run.compare("text-" + i, "sanitize_text_field", value(out, "text-" + i), TermText.sanitizeTextField(text));
            run.compare("lower-" + i, "strtolower", value(out, "lower-" + i), TermText.asciiLower(text));
            run.compare("int-" + i, "(int)", value(out, "int-" + i), Long.toString(TermRules.phpInt(text)));
        }
        for (int i = 0; i < INT_TEXTS.size(); i++) {
            run.compare(
                    "int-fixed-" + i,
                    "(int)",
                    value(out, "int-fixed-" + i),
                    Long.toString(TermRules.phpInt(INT_TEXTS.get(i))));
        }
        int fixedCount = fixedArgs().size();
        for (int i = 0; i < registrations.size(); i++) {
            if (i >= fixedCount) {
                run.generated("taxonomy-" + i, registrations.get(i).toString());
            }
            compare(
                    run,
                    "taxonomy-" + i,
                    definition("mood" + i, List.of("post", "post", "page"), registrations.get(i)),
                    out.get("taxonomy-" + i));
        }
        assertThat(BuiltInTaxonomies.all())
                .extracting(TaxonomyDefinition::key)
                .containsExactlyElementsOf(BUILT_IN_KEYS);
        for (String key : BUILT_IN_KEYS) {
            TaxonomyDefinition builtIn = BuiltInTaxonomies.all().stream()
                    .filter(definition -> definition.key().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("built-in taxonomy " + key + " is not registered"));
            compare(run, "builtin-" + key, builtIn, out.get("builtin-" + key));
        }
        for (int i = 0; i < orders.size(); i++) {
            run.compare(
                    "order-" + i,
                    "parse_order",
                    value(out, "order-" + i),
                    TermOrders.ascending(orders.get(i)) ? "ASC" : "DESC");
        }
        System.out.println("taxonomy parity: " + cases.size() + " cases, " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
        assertThat(run.comparisons()).isGreaterThanOrEqualTo(MIN_COMPARISONS);
    }

    private static ObjectNode op(String op, String key, String value) {
        return OracleJson.MAPPER.createObjectNode().put("op", op).put(key, value);
    }

    private static ObjectNode registration(String name, List<String> types, ObjectNode args) {
        ObjectNode in =
                OracleJson.MAPPER.createObjectNode().put("op", "taxonomy").put("name", name);
        ArrayNode list = in.putArray("object_types");
        types.forEach(list::add);
        in.set("args", args);
        return in;
    }

    private static String value(Map<String, JsonNode> out, String id) {
        return out.get(id).get("value").asText();
    }

    /** The definition QuarkPress's builder gives for the same arguments. */
    static TaxonomyDefinition definition(String name, List<String> types, JsonNode args) {
        TaxonomyDefinition.Builder builder = TaxonomyDefinition.builder(name, types);
        if (args.has("public")) {
            builder.publicly(args.get("public").booleanValue());
        }
        if (args.has("publicly_queryable")) {
            builder.publiclyQueryable(args.get("publicly_queryable").booleanValue());
        }
        if (args.has("hierarchical")) {
            builder.hierarchical(args.get("hierarchical").booleanValue());
        }
        if (args.has("show_ui")) {
            builder.showUi(args.get("show_ui").booleanValue());
        }
        if (args.has("show_in_menu")) {
            builder.showInMenu(args.get("show_in_menu").booleanValue());
        }
        if (args.has("show_in_nav_menus")) {
            builder.showInNavMenus(args.get("show_in_nav_menus").booleanValue());
        }
        if (args.has("show_tagcloud")) {
            builder.showTagcloud(args.get("show_tagcloud").booleanValue());
        }
        if (args.has("show_in_quick_edit")) {
            builder.showInQuickEdit(args.get("show_in_quick_edit").booleanValue());
        }
        if (args.has("show_admin_column")) {
            builder.showAdminColumn(args.get("show_admin_column").booleanValue());
        }
        if (args.has("show_in_rest")) {
            builder.showInRest(args.get("show_in_rest").booleanValue());
        }
        if (args.has("rest_base")) {
            builder.restBase(args.get("rest_base").textValue());
        }
        if (args.has("sort")) {
            builder.sort(args.get("sort").booleanValue());
        }
        if (args.has("label")) {
            builder.label(args.get("label").textValue());
        }
        if (args.has("labels")) {
            Map<String, String> labels = new LinkedHashMap<>();
            args.get("labels")
                    .properties()
                    .forEach(e -> labels.put(e.getKey(), e.getValue().textValue()));
            builder.labels(labels);
        }
        if (args.has("capabilities")) {
            Map<String, String> caps = new LinkedHashMap<>();
            args.get("capabilities")
                    .properties()
                    .forEach(e -> caps.put(e.getKey(), e.getValue().textValue()));
            builder.capabilities(caps);
        }
        JsonNode defaultTerm = args.get("default_term");
        if (defaultTerm != null && defaultTerm.isTextual()) {
            builder.defaultTerm(defaultTerm.textValue());
        } else if (defaultTerm != null) {
            builder.defaultTerm(
                    defaultTerm.get("name").textValue(),
                    defaultTerm.get("slug").textValue(),
                    defaultTerm.get("description").textValue());
        }
        return builder.build();
    }

    private static void compare(ParityRun run, String id, TaxonomyDefinition taxonomy, JsonNode wordpress) {
        run.compare(id, "name", wordpress.get("name").textValue(), taxonomy.key());
        List<String> types = new ArrayList<>();
        wordpress.get("object_type").forEach(type -> types.add(type.textValue()));
        run.compare(id, "object_type", String.join(",", types), String.join(",", taxonomy.objectTypes()));
        run.compare(id, "public", wordpress.get("public").booleanValue(), taxonomy.publicly());
        run.compare(
                id,
                "publicly_queryable",
                wordpress.get("publicly_queryable").booleanValue(),
                taxonomy.publiclyQueryable());
        run.compare(id, "hierarchical", wordpress.get("hierarchical").booleanValue(), taxonomy.hierarchical());
        run.compare(id, "show_ui", wordpress.get("show_ui").booleanValue(), taxonomy.showUi());
        run.compare(id, "show_in_menu", wordpress.get("show_in_menu").booleanValue(), taxonomy.showInMenu());
        run.compare(
                id, "show_in_nav_menus", wordpress.get("show_in_nav_menus").booleanValue(), taxonomy.showInNavMenus());
        run.compare(id, "show_tagcloud", wordpress.get("show_tagcloud").booleanValue(), taxonomy.showTagcloud());
        run.compare(
                id,
                "show_in_quick_edit",
                wordpress.get("show_in_quick_edit").booleanValue(),
                taxonomy.showInQuickEdit());
        run.compare(
                id, "show_admin_column", wordpress.get("show_admin_column").booleanValue(), taxonomy.showAdminColumn());
        for (String cap : TaxonomyDefinition.CAPABILITY_KEYS) {
            run.compare(id, "cap." + cap, wordpress.get("cap").get(cap).textValue(), taxonomy.capability(cap));
        }
        run.compare(id, "show_in_rest", wordpress.get("show_in_rest").booleanValue(), taxonomy.showInRest());
        JsonNode restBase = wordpress.get("rest_base");
        run.compare(
                id,
                "rest_base",
                restBase.isNull() ? "" : restBase.textValue(),
                taxonomy.restBase().orElse(""));
        JsonNode defaultTerm = wordpress.get("default_term");
        run.compare(
                id,
                "default_term",
                // Planned normalization: an empty text default term stays as given in WordPress and means none.
                defaultTerm.isNull() || defaultTerm.isTextual()
                        ? ""
                        : defaultTerm.get("name").asText() + "|"
                                + defaultTerm.get("slug").asText() + "|"
                                + defaultTerm.get("description").asText(),
                taxonomy.defaultTerm()
                        .map(term -> term.name() + "|" + term.slug() + "|" + term.description())
                        .orElse(""));
        run.compare(id, "sort", wordpress.get("sort").booleanValue(), taxonomy.sort());
        // Planned normalization: WordPress keeps the labels that do not apply to the kind as null; the driver drops
        // null-valued keys, and QuarkPress omits them. Key order is not compared.
        Map<String, String> labels = new TreeMap<>();
        wordpress
                .get("labels")
                .properties()
                .forEach(entry -> labels.put(entry.getKey(), entry.getValue().asText()));
        run.compare(id, "labels", labels.toString(), new TreeMap<>(taxonomy.labels()).toString());
    }
}
