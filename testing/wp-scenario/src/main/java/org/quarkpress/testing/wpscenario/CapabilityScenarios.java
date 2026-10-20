package org.quarkpress.testing.wpscenario;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.wporacle.GeneratedCases;

/**
 * The capability scenarios: hand-written ones per behaviour-reference rule, and generated ones drawn with test-gen
 * from fixed seeds (two sites, four users, a few membership, grant, super-admin and option changes, then many
 * capability checks), all on the subdirectory network.
 */
public final class CapabilityScenarios {

    static final String DIRECTORY = "scenarios/capabilities/";

    /** Generated scenarios per seed. */
    public static final int GENERATED = 20;

    static final List<String> ROLES = List.of("administrator", "editor", "author", "contributor", "subscriber", "");

    static final List<String> GRANTABLE = List.of(
            "edit_posts",
            "publish_posts",
            "upload_files",
            "manage_options",
            "edit_others_posts",
            "list_users",
            "promote_users",
            "remove_users",
            "delete_users",
            "editor",
            "unfiltered_html",
            "create_users",
            "do_not_allow");

    static final List<String> CHECKED = List.of(
            "read",
            "exist",
            "do_not_allow",
            "frobnicate",
            "edit_posts",
            "publish_posts",
            "edit_others_posts",
            "edit_pages",
            "upload_files",
            "manage_options",
            "moderate_comments",
            "manage_links",
            "manage_categories",
            "list_users",
            "promote_users",
            "remove_users",
            "unfiltered_html",
            "edit_css",
            "update_core",
            "install_plugins",
            "activate_plugins",
            "install_languages",
            "view_site_health_checks",
            "resume_plugin",
            "delete_users",
            "create_users",
            "add_users",
            "edit_users",
            "delete_site",
            "customize",
            "setup_network",
            "manage_privacy_options",
            "manage_network",
            "create_sites",
            "manage_sites",
            "manage_network_options",
            "editor",
            "administrator",
            "level_7",
            "level_0");

    static final List<String> USER_CHECKS = List.of("edit_user", "promote_user", "remove_user", "delete_user");

    static final List<String> WORDS = List.of("ann", "bo", "cy", "dee", "ed", "lee", "kim");
    static final List<String> SEPARATORS = List.of(".", " ", "-", "_", "@");
    static final List<String> NAMES = List.of("Ann", "Lee", "Zoe", "Kim", "Ola");

    private CapabilityScenarios() {}

    /** The hand-written scenarios listed in {@code scenarios/capabilities/index.txt}, in that order. */
    public static List<Scenario> handWritten() {
        return ScenarioIndex.load(DIRECTORY);
    }

    /** The hand-written scenarios, then the generated ones of two seeds. */
    public static List<Scenario> all() {
        List<Scenario> scenarios = new ArrayList<>(handWritten());
        scenarios.addAll(generated("20261018-caps-1", GENERATED));
        scenarios.addAll(generated("20261018-caps-2", GENERATED));
        return List.copyOf(scenarios);
    }

    public static List<Scenario> generated(String seed, int count) {
        List<List<ScenarioOp>> drawn = GeneratedCases.sample(ops(), seed, count);
        List<Scenario> scenarios = new ArrayList<>(drawn.size());
        String suffix = seed.substring(seed.lastIndexOf('-') + 1).toLowerCase(Locale.ROOT);
        for (int i = 0; i < drawn.size(); i++) {
            scenarios.add(new Scenario(
                    "gen-caps-" + suffix + "-" + i, "generated", NetworkVariant.SUBDIRECTORY, drawn.get(i), true));
        }
        return scenarios;
    }

    static Gen<List<ScenarioOp>> ops() {
        return random -> {
            List<ScenarioOp> ops = new ArrayList<>();
            ops.add(new ScenarioOp.CreateSite("localhost", "/c" + random.nextInt(100_000) + "/", "Caps"));
            ops.add(new ScenarioOp.CreateSite("caps" + random.nextInt(100_000) + ".example", "/", "Caps two"));
            int users = 4;
            Set<String> logins = new HashSet<>();
            for (int user = 1; user <= users; user++) {
                ops.add(user(random, user, logins));
            }
            int changes = 3 + random.nextInt(8);
            for (int i = 0; i < changes; i++) {
                ops.add(change(random, users));
            }
            int checks = 15 + random.nextInt(20);
            for (int i = 0; i < checks; i++) {
                ops.add(check(random, users));
            }
            return ops;
        };
    }

    /** A user whose login differs from the others' (case-insensitively); names given half of the time. */
    private static ScenarioOp.CreateUser user(RandomGenerator random, int index, Set<String> logins) {
        String username;
        do {
            username = pick(random, WORDS) + pick(random, SEPARATORS) + pick(random, WORDS);
            if (random.nextInt(3) == 0) {
                username = username + index;
            }
        } while (!logins.add(username.toLowerCase(Locale.ROOT)) || username.equalsIgnoreCase("admin"));
        if (random.nextBoolean()) {
            return new ScenarioOp.CreateUser(
                    username, pick(random, NAMES), random.nextBoolean() ? pick(random, NAMES) : "");
        }
        return new ScenarioOp.CreateUser(username, "", "");
    }

    private static ScenarioOp change(RandomGenerator random, int users) {
        int user = 1 + random.nextInt(users);
        int site = random.nextInt(3);
        return switch (random.nextInt(9)) {
            case 0, 1, 2 -> new ScenarioOp.AddUser(user, site, pick(random, ROLES));
            case 3 -> new ScenarioOp.SetRole(user, site, pick(random, ROLES));
            case 4 -> new ScenarioOp.AddCap(user, site, pick(random, GRANTABLE), random.nextBoolean());
            case 5 -> new ScenarioOp.SuperAdmin(user, random.nextInt(3) != 0);
            case 6 ->
                new ScenarioOp.NetworkFlag(
                        random.nextBoolean() ? "add_new_users" : "menu_plugins", random.nextBoolean());
            case 7 -> new ScenarioOp.SiteSetting(site, "link_manager_enabled", random.nextBoolean());
            default -> new ScenarioOp.RemoveUser(user, site);
        };
    }

    private static ScenarioOp check(RandomGenerator random, int users) {
        int user = random.nextInt(users + 1);
        int site = random.nextInt(3);
        if (random.nextInt(4) == 0) {
            return new ScenarioOp.Can(user, site, pick(random, USER_CHECKS), 1 + random.nextInt(users));
        }
        return new ScenarioOp.Can(user, site, pick(random, CHECKED), 0);
    }

    private static <T> T pick(RandomGenerator random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
