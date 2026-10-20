package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.WordPressKeys;
import org.quarkpress.core.runtime.SiteZones;
import org.quarkpress.posts.PostException;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * Compares the posts module's pure rules with WordPress 7.1.3's functions (testing/wp-oracle): slugs, slug
 * truncation, keys, post type and post status registration, and local/GMT conversions. Active only with
 * {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class PostsParityTest {

    static final Path DRIVER = Path.of("src/test/php/posts-driver.php");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-posts-failures.json");
    static final String[] WORDPRESS_FILES = {
        "wp-includes/formatting.php",
        "wp-includes/functions.php",
        "wp-includes/post.php",
        "wp-includes/class-wp-post-type.php"
    };

    /** Half of what the first green run compared, rounded down to a multiple of 50. */
    static final int MIN_COMPARISONS = 22_700;

    /** Hand-written cases come first in the type and status lists; the rest are generated. */
    static final int HAND_TYPES = 12;

    static final int HAND_STATUSES = 5;

    static final int HAND_CONVERSIONS = 6;

    static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    /** Zones east and west of UTC, with whole-hour, half-hour and quarter-hour offsets and both hemispheres. */
    static final String[] TRANSITION_ZONES = {
        "America/New_York",
        "Europe/London",
        "Europe/Paris",
        "Australia/Sydney",
        "Pacific/Auckland",
        "Asia/Kolkata",
        "America/St_Johns",
        "Australia/Lord_Howe"
    };

    /** Seconds around a transition's boundary: before, inside and after a gap or an overlap. */
    static final int[] AROUND = {-7200, -3600, -1800, -1, 0, 1, 1799, 1800, 1801, 3599, 3600, 3601, 7200};

    /** Date texts with their case ids: bad and edge dates for the post-date check. */
    static final String[][] DATE_TABLE = {
        {"leap", "2024-02-29 12:00:00"},
        {"nonleap", "2023-02-29 12:00:00"},
        {"feb30", "2026-02-30 12:00:00"},
        {"feb31", "2026-02-31 00:00:00"},
        {"apr31", "2026-04-31 00:00:00"},
        {"century", "1900-02-29 00:00:00"},
        {"quadcentury", "2000-02-29 00:00:00"},
        {"zero", "0000-00-00 00:00:00"},
        {"month13", "2026-13-01 00:00:00"},
        {"month0", "2026-00-10 00:00:00"},
        {"day0", "2026-05-00 00:00:00"},
        {"hour24", "2026-05-05 24:00:00"},
        {"hour25", "2026-05-05 25:00:00"},
        {"minute60", "2026-05-05 10:60:00"},
        {"year999", "999-05-05 10:00:00"},
        {"year0999", "0999-05-05 10:00:00"},
        {"year1000", "1000-01-01 00:00:00"},
        {"year9999", "9999-12-31 23:59:59"},
        {"year10000", "10000-01-01 00:00:00"},
        {"text", "not a date"},
        {"empty", ""}
    };

    static final List<String[]> ZONES = List.of(
            new String[] {"Europe/Paris", "0"},
            new String[] {"America/New_York", "0"},
            new String[] {"Australia/Lord_Howe", "0"},
            new String[] {"Asia/Kathmandu", "0"},
            new String[] {"", "5.5"},
            new String[] {"", "-3.75"},
            new String[] {"", "0"});

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void matchesWordPress() throws Exception {
        Path sources = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(sources);
        ParityRun run = new ParityRun("posts", ParitySkipList.load(SKIPS), FAILURES);
        List<PhpOracle.Case> cases = new ArrayList<>();

        List<String> titles = new ArrayList<>(List.of(
                "Hello World!",
                "Caf" + cp(0xE9) + " cr" + cp(0xE8) + "me",
                cp(0xDC) + "n" + cp(0xEF) + "c" + cp(0xF6) + "d" + cp(0xE9) + " " + cp(0x2014) + " test",
                "  --Hello--  ",
                cp(0x41F, 0x440, 0x438, 0x432, 0x435, 0x442) + " " + cp(0x43C, 0x438, 0x440),
                cp(0x65E5, 0x672C, 0x8A9E),
                "100% done",
                "a/b/c",
                "x.y.z",
                "<b>Bold</b> title",
                "Rock &amp; Roll",
                "Hello&nbsp;World",
                "Don" + cp(0x2019) + "t stop",
                "2 " + cp(0xD7) + " 3",
                cp(0xDF) + " stra" + cp(0xDF) + "e",
                "%E6%97%A5",
                "foo%zz",
                "__trashed",
                "a".repeat(300),
                cp(0x65E5).repeat(80),
                "a".repeat(199) + "-b",
                cp(0x1F389) + " Party",
                "a---41---b",
                "0",
                "",
                "!!!",
                "Tab\tand\nline",
                cp(0x130) + "stanbul " + cp(0x3A3, 0x3A3),
                "e" + cp(0x301) + "te",
                "a" + cp(0xA0) + "b" + cp(0x2009) + "c" + cp(0x200B) + "d"));
        int hand = titles.size();
        for (int c = 0x80; c <= 0x24F; c++) {
            if (Character.isDefined(c) && !Character.isISOControl(c)) {
                titles.add("x" + cp(c) + "y");
            }
        }
        for (int c = 0x2000; c <= 0x206F; c++) {
            if (Character.isDefined(c)) {
                titles.add("x" + cp(c) + "y");
            }
        }
        String alphabet = "aZ09 -_.%/&;<>!#\t" + cp(0xE9, 0x418, 0x65E5, 0x2014, 0xA0, 0xD7, 0x2019, 0x2009);
        titles.addAll(GeneratedCases.sample(Gens.strings(alphabet, 0, 60), "20261020-post-slugs", 800));
        for (int i = 0; i < titles.size(); i++) {
            String title = titles.get(i);
            cases.add(new PhpOracle.Case("save-" + i, title(title, "save", "")));
            cases.add(new PhpOracle.Case("query-" + i, title(title, "query", "")));
            cases.add(new PhpOracle.Case("fallback-" + i, title(title, "save", "77")));
            cases.add(new PhpOracle.Case("key-" + i, text("sanitize_key", title)));
            cases.add(new PhpOracle.Case("urlencode-" + i, text("urlencode", title)));
        }

        List<String[]> truncations = new ArrayList<>();
        for (String slug : List.of(
                "a".repeat(200),
                "%e6%97%a5".repeat(22),
                "ab-".repeat(66) + "ab",
                "hello-",
                "%41".repeat(70),
                "x+y".repeat(70))) {
            for (int length : new int[] {191, 197, 198, 200}) {
                truncations.add(new String[] {slug, Integer.toString(length)});
            }
        }
        Gen<String> slugs = Gens.strings("ab-%e6%97%a5%c3%a9", 150, 230);
        for (String slug : GeneratedCases.sample(slugs, "20261020-post-truncate", 200)) {
            truncations.add(new String[] {slug, "197"});
        }
        for (int i = 0; i < truncations.size(); i++) {
            ObjectNode in = OracleJson.MAPPER
                    .createObjectNode()
                    .put("op", "truncate")
                    .put("slug", truncations.get(i)[0])
                    .put("length", Integer.parseInt(truncations.get(i)[1]));
            cases.add(new PhpOracle.Case("truncate-" + i, in));
        }

        List<ObjectNode> typeArgs = typeArguments();
        for (int i = 0; i < typeArgs.size(); i++) {
            cases.add(new PhpOracle.Case("type-" + i, typeArgs.get(i)));
        }
        List<ObjectNode> statusArgs = statusArguments();
        for (int i = 0; i < statusArgs.size(); i++) {
            cases.add(new PhpOracle.Case("status-" + i, statusArgs.get(i)));
        }

        List<String[]> conversions = conversions();
        for (int i = 0; i < conversions.size(); i++) {
            String[] conversion = conversions.get(i);
            ObjectNode options = OracleJson.MAPPER
                    .createObjectNode()
                    .put("timezone_string", conversion[0])
                    .put("gmt_offset", conversion[1]);
            ObjectNode toGmt = OracleJson.MAPPER
                    .createObjectNode()
                    .put("op", "gmt_from_date")
                    .put("date", conversion[2]);
            toGmt.set("options", options);
            ObjectNode toLocal = OracleJson.MAPPER
                    .createObjectNode()
                    .put("op", "date_from_gmt")
                    .put("date", conversion[2]);
            toLocal.set("options", options.deepCopy());
            cases.add(new PhpOracle.Case("gmt-" + i, toGmt));
            cases.add(new PhpOracle.Case("local-" + i, toLocal));
        }
        for (String[] row : DATE_TABLE) {
            cases.add(new PhpOracle.Case(
                    "date-" + row[0],
                    OracleJson.MAPPER.createObjectNode().put("op", "valid_date").put("date", row[1])));
        }
        cases.add(
                new PhpOracle.Case("intl", OracleJson.MAPPER.createObjectNode().put("op", "intl")));

        Map<String, JsonNode> out = oracle.run(DRIVER, cases);
        boolean intl = "yes".equals(value(out, "intl"));
        if (!intl && "true".equalsIgnoreCase(System.getenv("CI"))) {
            throw new AssertionError("CI requires a PHP with the intl extension: " + System.getProperty("wp.php"));
        }
        int skippedNoIntl = 0;

        for (int i = 0; i < titles.size(); i++) {
            String title = titles.get(i);
            if (!intl && decomposed(title)) {
                skippedNoIntl++;
                continue;
            }
            if (i >= hand) {
                run.generated("save-" + i, OracleJson.MAPPER.writeValueAsString(title));
            }
            run.compare("save-" + i, "sanitize_title", value(out, "save-" + i), Slugs.sanitizeTitle(title));
            run.compare(
                    "query-" + i,
                    "sanitize_title_for_query",
                    value(out, "query-" + i),
                    Slugs.sanitizeTitleForQuery(title));
            run.compare(
                    "fallback-" + i,
                    "sanitize_title_fallback",
                    value(out, "fallback-" + i),
                    Slugs.sanitizeTitle(title, "77"));
            run.compare("key-" + i, "sanitize_key", value(out, "key-" + i), WordPressKeys.sanitize(title));
            run.compare(
                    "urlencode-" + i,
                    "urlencode",
                    value(out, "urlencode-" + i),
                    Slugs.phpUrlencode(title).toLowerCase(Locale.ROOT));
        }
        for (int i = 0; i < truncations.size(); i++) {
            String[] truncation = truncations.get(i);
            run.compare(
                    "truncate-" + i,
                    "_truncate_post_slug",
                    value(out, "truncate-" + i),
                    Slugs.truncate(truncation[0], Integer.parseInt(truncation[1])));
        }
        for (int i = 0; i < typeArgs.size(); i++) {
            if (i >= HAND_TYPES) {
                run.generated("type-" + i, typeArgs.get(i).toString());
            }
            compareType(run, "type-" + i, typeArgs.get(i), out.get("type-" + i));
        }
        for (int i = 0; i < statusArgs.size(); i++) {
            if (i >= HAND_STATUSES) {
                run.generated("status-" + i, statusArgs.get(i).toString());
            }
            compareStatus(run, "status-" + i, statusArgs.get(i), out.get("status-" + i));
        }
        for (String[] row : DATE_TABLE) {
            run.compare("date-" + row[0], "valid_date", value(out, "date-" + row[0]), quarkPressAccepts(row[1]));
        }
        for (int i = 0; i < conversions.size(); i++) {
            String[] conversion = conversions.get(i);
            if (i >= HAND_CONVERSIONS) {
                String input = conversion[0] + " " + conversion[1] + " " + conversion[2];
                run.generated("gmt-" + i, input);
                run.generated("local-" + i, input);
            }
            ZoneId zone = SiteZones.zoneOf(conversion[0], conversion[1]);
            LocalDateTime wall = LocalDateTime.parse(conversion[2].replace(' ', 'T'));
            run.compare(
                    "gmt-" + i,
                    "get_gmt_from_date",
                    value(out, "gmt-" + i),
                    PostDates.format(LocalDateTime.ofInstant(PostDates.toInstant(wall, zone), ZoneOffset.UTC)));
            run.compare(
                    "local-" + i,
                    "get_date_from_gmt",
                    value(out, "local-" + i),
                    PostDates.format(PostDates.toLocal(wall.toInstant(ZoneOffset.UTC), zone)));
        }

        if (!intl) {
            System.out.println("ASSUMPTION: PHP has no intl extension; skipped " + skippedNoIntl
                    + " cases with decomposed input (set CI to make this a failure)");
        }
        System.out.println("posts parity: " + cases.size() + " cases, " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
        assertThat(run.comparisons()).isGreaterThanOrEqualTo(MIN_COMPARISONS);
    }

    /** Hand-written post type arguments, then generated combinations of the flags QuarkPress keeps. */
    private static List<ObjectNode> typeArguments() {
        List<ObjectNode> list = new ArrayList<>();
        list.add(type("Book", args()));
        list.add(type("book", args().put("public", true)));
        list.add(type("hidden", args().put("show_ui", false).put("show_in_menu", true)));
        ObjectNode ledger = args();
        ledger.putArray("capability_type").add("entry").add("entries");
        list.add(type("ledger", ledger));
        ObjectNode withCaps = args();
        withCaps.putObject("capabilities").put("create_posts", "upload_files");
        list.add(type("doc", withCaps));
        list.add(type("chapter", args().put("hierarchical", true)));
        ObjectNode named = args().put("hierarchical", true);
        named.putObject("labels").put("name", "Chapters").put("singular_name", "Chapter");
        list.add(type("chapter", named));
        list.add(type("movie", args().put("label", "Movies")));
        ObjectNode supportsTitle = args();
        supportsTitle.putArray("supports").add("title");
        list.add(type("a", supportsTitle));
        ObjectNode supportsEmpty = args();
        supportsEmpty.putArray("supports");
        list.add(type("b", supportsEmpty));
        list.add(type("c", args().put("supports", false)));
        ObjectNode editorTwice = args();
        editorTwice.putArray("supports").add("editor").add("title").add("editor");
        list.add(type("d", editorTwice));
        RandomGenerator random = Properties.random(Properties.seedOf("20261020-post-types"));
        String[] flags = {
            "public",
            "hierarchical",
            "exclude_from_search",
            "publicly_queryable",
            "embeddable",
            "show_ui",
            "show_in_menu",
            "show_in_nav_menus",
            "show_in_admin_bar",
            "map_meta_cap"
        };
        String[] features = {"title", "editor", "author", "excerpt", "autosave", "comments"};
        String[] labelKeys = {"name", "singular_name", "menu_name", "all_items", "add_new_item", "template_name"};
        for (int i = 0; i < 300; i++) {
            ObjectNode args = args();
            for (String flag : flags) {
                int choice = random.nextInt(3);
                if (choice < 2) {
                    args.put(flag, choice == 1);
                }
            }
            if (random.nextInt(4) == 0) {
                args.put("capability_type", random.nextBoolean() ? "page" : "thing");
            }
            if (random.nextInt(3) == 0) {
                ArrayNode supports = args.putArray("supports");
                for (String feature : features) {
                    if (random.nextBoolean()) {
                        supports.add(feature);
                    }
                }
            }
            if (random.nextInt(3) == 0) {
                ObjectNode labels = args.putObject("labels");
                for (String key : labelKeys) {
                    if (random.nextInt(3) == 0) {
                        labels.put(key, "L" + key.length());
                    }
                }
            }
            if (random.nextInt(5) == 0) {
                args.put("label", "Things");
            }
            list.add(type("t" + i, args));
        }
        return list;
    }

    private static List<ObjectNode> statusArguments() {
        List<ObjectNode> list = new ArrayList<>();
        list.add(status("archived", args()));
        list.add(status("archived", args().put("public", true)));
        list.add(status("Needs Review", args()));
        list.add(status("hidden", args().put("private", true)));
        list.add(status("labelled", args().put("label", "Labelled").put("date_floating", true)));
        RandomGenerator random = Properties.random(Properties.seedOf("20261020-post-statuses"));
        String[] flags = {
            "public",
            "protected",
            "private",
            "internal",
            "publicly_queryable",
            "exclude_from_search",
            "show_in_admin_all_list",
            "show_in_admin_status_list",
            "date_floating"
        };
        for (int i = 0; i < 200; i++) {
            ObjectNode args = args();
            for (String flag : flags) {
                int choice = random.nextInt(3);
                if (choice < 2) {
                    args.put(flag, choice == 1);
                }
            }
            list.add(status("s" + i, args));
        }
        return list;
    }

    /** Zone, offset and wall time: daylight-saving gaps and overlaps, then generated times in every zone. */
    private static List<String[]> conversions() {
        List<String[]> list = new ArrayList<>();
        list.add(new String[] {"America/New_York", "0", "2024-03-10 02:30:00"});
        list.add(new String[] {"America/New_York", "0", "2024-11-03 01:30:00"});
        list.add(new String[] {"Europe/Paris", "0", "2026-03-29 02:30:00"});
        list.add(new String[] {"Europe/Paris", "0", "2026-10-25 02:30:00"});
        list.add(new String[] {"Australia/Lord_Howe", "0", "2026-10-04 02:15:00"});
        list.add(new String[] {"", "5.5", "2026-01-15 10:00:00"});
        RandomGenerator random = Properties.random(Properties.seedOf("20261020-post-dates"));
        for (int i = 0; i < 400; i++) {
            String[] zone = ZONES.get(random.nextInt(ZONES.size()));
            LocalDateTime wall = LocalDateTime.of(1990, 1, 1, 0, 0).plusSeconds(random.nextLong(60L * 365 * 24 * 3600));
            list.add(new String[] {zone[0], zone[1], PostDates.format(wall)});
        }
        list.addAll(transitionConversions());
        return list;
    }

    /**
     * For each named zone, every transition from 2000 to 2037 taken from the zone rules: wall times (read as local
     * and as GMT) from two hours before to two hours after the boundary, on both sides of it, so each gap and
     * overlap is entered, crossed and left, to the second.
     */
    private static List<String[]> transitionConversions() {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> list = new ArrayList<>();
        Instant end = LocalDateTime.of(2038, 1, 1, 0, 0).toInstant(ZoneOffset.UTC);
        for (String name : TRANSITION_ZONES) {
            ZoneRules rules = ZoneId.of(name).getRules();
            Instant at = LocalDateTime.of(2000, 1, 1, 0, 0).toInstant(ZoneOffset.UTC);
            ZoneOffsetTransition transition = rules.nextTransition(at);
            while (transition != null && transition.getInstant().isBefore(end)) {
                List<LocalDateTime> bases = List.of(
                        transition.getDateTimeBefore(),
                        transition.getDateTimeAfter(),
                        LocalDateTime.ofInstant(transition.getInstant(), ZoneOffset.UTC));
                for (LocalDateTime base : bases) {
                    for (int seconds : AROUND) {
                        String text = PostDates.format(base.plusSeconds(seconds));
                        if (seen.add(name + "|" + text)) {
                            list.add(new String[] {name, "0", text});
                        }
                    }
                }
                transition = rules.nextTransition(transition.getInstant());
            }
        }
        return list;
    }

    /** What the posts module does with a date text: dates are typed, so it must read as a storable date. */
    private static String quarkPressAccepts(String text) {
        // Planned normalization: an empty or all-zero text is WordPress's "no date given", which PostDates takes
        // as an absent date (the save then uses now), so it is accepted here.
        if (text.isEmpty() || text.equals("0000-00-00 00:00:00")) {
            return "valid";
        }
        try {
            PostDates.requireStorable(LocalDateTime.parse(text, STRICT_DATE));
            return "valid";
        } catch (DateTimeException | PostException e) {
            return "invalid";
        }
    }

    private static void compareType(ParityRun run, String id, ObjectNode in, JsonNode wordpress) {
        PostTypeDefinition type = definition(in.get("name").textValue(), in.get("args"));
        run.compare(id, "name", wordpress.get("name").textValue(), type.key());
        run.compare(id, "public", wordpress.get("public").booleanValue(), type.publicly());
        run.compare(id, "hierarchical", wordpress.get("hierarchical").booleanValue(), type.hierarchical());
        run.compare(
                id,
                "exclude_from_search",
                wordpress.get("exclude_from_search").booleanValue(),
                type.excludeFromSearch());
        run.compare(
                id, "publicly_queryable", wordpress.get("publicly_queryable").booleanValue(), type.publiclyQueryable());
        run.compare(id, "embeddable", wordpress.get("embeddable").booleanValue(), type.embeddable());
        run.compare(id, "show_ui", wordpress.get("show_ui").booleanValue(), type.showUi());
        run.compare(id, "show_in_menu", wordpress.get("show_in_menu").booleanValue(), type.showInMenu());
        run.compare(id, "show_in_nav_menus", wordpress.get("show_in_nav_menus").booleanValue(), type.showInNavMenus());
        run.compare(id, "show_in_admin_bar", wordpress.get("show_in_admin_bar").booleanValue(), type.showInAdminBar());
        run.compare(id, "map_meta_cap", wordpress.get("map_meta_cap").booleanValue(), type.mapMetaCap());
        run.compare(id, "capability_type", wordpress.get("capability_type").textValue(), type.capabilityType());
        run.compare(
                id,
                "edit_posts",
                wordpress.get("edit_posts").textValue(),
                PostTypeCapabilities.of(
                                type.key(),
                                type.capabilityType(),
                                type.capabilityTypePlural(),
                                type.mapMetaCap(),
                                type.capabilities())
                        .editPosts());
        List<String> supports = new ArrayList<>();
        wordpress.get("supports").forEach(feature -> supports.add(feature.textValue()));
        run.compare(id, "supports", String.join(",", supports), String.join(",", type.supports()));
        // Planned normalization: WordPress keeps unset labels (flat types' parent_item_colon) as null; the driver
        // drops null-valued keys, and QuarkPress omits them.
        Map<String, String> labels = new LinkedHashMap<>();
        wordpress
                .get("labels")
                .properties()
                .forEach(entry -> labels.put(entry.getKey(), entry.getValue().asText()));
        run.compare(id, "labels", labels.toString(), type.labels().toString());
    }

    private static void compareStatus(ParityRun run, String id, ObjectNode in, JsonNode wordpress) {
        PostStatusDefinition status = statusDefinition(in.get("name").textValue(), in.get("args"));
        run.compare(id, "name", wordpress.get("name").textValue(), status.name());
        run.compare(id, "label", wordpress.get("label").textValue(), status.label());
        run.compare(
                id,
                "label_count",
                wordpress.get("label_count").get(0).textValue() + "|"
                        + wordpress.get("label_count").get(1).textValue(),
                status.labelCountSingular() + "|" + status.labelCountPlural());
        run.compare(id, "public", wordpress.get("public").booleanValue(), status.publicly());
        run.compare(id, "protected", wordpress.get("protected").booleanValue(), status.protectedStatus());
        run.compare(id, "private", wordpress.get("private").booleanValue(), status.privateStatus());
        run.compare(id, "internal", wordpress.get("internal").booleanValue(), status.internal());
        run.compare(
                id,
                "publicly_queryable",
                wordpress.get("publicly_queryable").booleanValue(),
                status.publiclyQueryable());
        run.compare(
                id,
                "exclude_from_search",
                wordpress.get("exclude_from_search").booleanValue(),
                status.excludeFromSearch());
        run.compare(
                id,
                "show_in_admin_all_list",
                wordpress.get("show_in_admin_all_list").booleanValue(),
                status.showInAdminAllList());
        run.compare(
                id,
                "show_in_admin_status_list",
                wordpress.get("show_in_admin_status_list").booleanValue(),
                status.showInAdminStatusList());
        run.compare(id, "date_floating", wordpress.get("date_floating").booleanValue(), status.dateFloating());
    }

    /** The QuarkPress registration of the same arguments. */
    static PostTypeDefinition definition(String name, JsonNode args) {
        PostTypeDefinition.Builder builder = PostTypeDefinition.builder(name);
        if (args.has("public")) {
            builder.publicly(args.get("public").booleanValue());
        }
        if (args.has("hierarchical")) {
            builder.hierarchical(args.get("hierarchical").booleanValue());
        }
        if (args.has("exclude_from_search")) {
            builder.excludeFromSearch(args.get("exclude_from_search").booleanValue());
        }
        if (args.has("publicly_queryable")) {
            builder.publiclyQueryable(args.get("publicly_queryable").booleanValue());
        }
        if (args.has("embeddable")) {
            builder.embeddable(args.get("embeddable").booleanValue());
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
        if (args.has("show_in_admin_bar")) {
            builder.showInAdminBar(args.get("show_in_admin_bar").booleanValue());
        }
        if (args.has("map_meta_cap")) {
            builder.mapMetaCap(args.get("map_meta_cap").booleanValue());
        }
        JsonNode capabilityType = args.get("capability_type");
        if (capabilityType != null && capabilityType.isArray()) {
            builder.capabilityType(
                    capabilityType.get(0).textValue(), capabilityType.get(1).textValue());
        } else if (capabilityType != null) {
            builder.capabilityType(capabilityType.textValue());
        }
        if (args.has("capabilities")) {
            Map<String, String> capabilities = new LinkedHashMap<>();
            args.get("capabilities")
                    .properties()
                    .forEach(e -> capabilities.put(e.getKey(), e.getValue().textValue()));
            builder.capabilities(capabilities);
        }
        JsonNode supports = args.get("supports");
        if (supports != null && supports.isBoolean()) {
            builder.noSupports();
        } else if (supports != null) {
            List<String> features = new ArrayList<>();
            supports.forEach(feature -> features.add(feature.textValue()));
            builder.supports(features);
        }
        if (args.has("labels")) {
            Map<String, String> labels = new LinkedHashMap<>();
            args.get("labels")
                    .properties()
                    .forEach(e -> labels.put(e.getKey(), e.getValue().textValue()));
            builder.labels(labels);
        }
        if (args.has("label")) {
            builder.label(args.get("label").textValue());
        }
        return builder.build();
    }

    static PostStatusDefinition statusDefinition(String name, JsonNode args) {
        PostStatusDefinition.Builder builder = PostStatusDefinition.builder(name);
        if (args.has("label")) {
            builder.label(args.get("label").textValue());
        }
        if (args.has("public")) {
            builder.publicly(args.get("public").booleanValue());
        }
        if (args.has("protected")) {
            builder.protectedStatus(args.get("protected").booleanValue());
        }
        if (args.has("private")) {
            builder.privateStatus(args.get("private").booleanValue());
        }
        if (args.has("internal")) {
            builder.internal(args.get("internal").booleanValue());
        }
        if (args.has("publicly_queryable")) {
            builder.publiclyQueryable(args.get("publicly_queryable").booleanValue());
        }
        if (args.has("exclude_from_search")) {
            builder.excludeFromSearch(args.get("exclude_from_search").booleanValue());
        }
        if (args.has("show_in_admin_all_list")) {
            builder.showInAdminAllList(args.get("show_in_admin_all_list").booleanValue());
        }
        if (args.has("show_in_admin_status_list")) {
            builder.showInAdminStatusList(args.get("show_in_admin_status_list").booleanValue());
        }
        if (args.has("date_floating")) {
            builder.dateFloating(args.get("date_floating").booleanValue());
        }
        return builder.build();
    }

    private static ObjectNode args() {
        return OracleJson.MAPPER.createObjectNode();
    }

    private static ObjectNode type(String name, ObjectNode args) {
        ObjectNode in =
                OracleJson.MAPPER.createObjectNode().put("op", "post_type").put("name", name);
        in.set("args", args);
        return in;
    }

    private static ObjectNode status(String name, ObjectNode args) {
        ObjectNode in =
                OracleJson.MAPPER.createObjectNode().put("op", "post_status").put("name", name);
        in.set("args", args);
        return in;
    }

    private static ObjectNode title(String text, String context, String fallback) {
        return OracleJson.MAPPER
                .createObjectNode()
                .put("op", "sanitize_title")
                .put("text", text)
                .put("context", context)
                .put("fallback", fallback);
    }

    private static ObjectNode text(String op, String text) {
        return OracleJson.MAPPER.createObjectNode().put("op", op).put("text", text);
    }

    private static String value(Map<String, JsonNode> out, String id) {
        JsonNode node = out.get(id);
        if (node == null || node.get("value") == null) {
            throw new AssertionError("the driver gave no value for " + id + ": " + node);
        }
        return node.get("value").asText();
    }

    /** True when the text holds a non-spacing mark, so remove_accents' answer depends on intl. */
    private static boolean decomposed(String text) {
        return text.codePoints().anyMatch(c -> Character.getType(c) == Character.NON_SPACING_MARK);
    }
}
