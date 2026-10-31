package org.quarkpress.testing.wprestparity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wprestparity.RestScenario.SetupStep;

/**
 * The REST parity scenarios: the hand-written ones ({@code scenarios/index.txt}, Part D 1-28 first) and generated ones
 * over callers, routes, methods and query parameters drawn from each route's declared arguments.
 *
 * <p>Generated requests come from six callers (anonymous and the five dev-realm users) and stay clear of the classes of
 * request whose answers are known differences as a whole, so the known-differences file can name them by scenario
 * (scope accepted by the controller; each left-out class is covered by a hand-written scenario or is a known
 * difference): no {@code invalid} caller (every answer differs, D2), no
 * {@code Origin} (D4), no {@code _jsonp} (D5), no successful user writes (D10; alice never writes users, nobody writes
 * to themselves), only the types and taxonomies QuarkPress registers (D8), no plain permalinks (D1). Orderings whose
 * ties have no defined order on either side (registration date, term group, description, count, url) are not drawn.
 */
public final class RestScenarios {

    /** Generated scenarios per seed. */
    public static final int GENERATED = 60;

    /** The seeds of {@link #all()}. */
    public static final List<String> SEEDS = List.of("m2c-a", "m2c-b");

    static final String RESOURCES = "scenarios/";

    private static final List<String> GENERATED_CALLERS = List.of("anonymous", "alice", "bob", "carol", "dave", "erin");
    private static final List<String> USERS = List.of("alice", "bob", "carol", "dave", "erin");

    private RestScenarios() {}

    /** The hand-written scenarios, in {@code index.txt} order. */
    public static List<RestScenario> handWritten() {
        List<RestScenario> out = new ArrayList<>();
        for (String file : index()) {
            String json = resource(file);
            try {
                out.add(RestScenarioJson.read(json));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(RESOURCES + file + ": " + e.getMessage(), e);
            }
        }
        return List.copyOf(out);
    }

    /** {@code count} generated scenarios, the same for the same seed on every run; ids {@code <seed>-<nnn>}. */
    public static List<RestScenario> generated(String seed, int count) {
        List<Draft> drafts = GeneratedCases.sample(DRAFTS, seed, count);
        List<RestScenario> out = new ArrayList<>(count);
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            out.add(new RestScenario(
                    "%s-%03d".formatted(seed, i + 1), "generated (seed " + seed + ")", draft.setup, draft.requests));
        }
        return List.copyOf(out);
    }

    /** The hand-written scenarios, then {@link #GENERATED} per seed of {@link #SEEDS}. */
    public static List<RestScenario> all() {
        List<RestScenario> out = new ArrayList<>(handWritten());
        for (String seed : SEEDS) {
            out.addAll(generated(seed, GENERATED));
        }
        return List.copyOf(out);
    }

    /** The scenario file names listed in {@code index.txt}. */
    static List<String> index() {
        List<String> files = new ArrayList<>();
        for (String line : resource("index.txt").lines().toList()) {
            String name = line.strip();
            if (!name.isEmpty() && !name.startsWith("#")) {
                files.add(name);
            }
        }
        return List.copyOf(files);
    }

    static String resource(String name) {
        try (InputStream in = RestScenarios.class.getResourceAsStream(RESOURCES + name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCES + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- generation ---------------------------------------------------------------------------------------------

    /** A generated scenario before it has an id. */
    private record Draft(List<SetupStep> setup, List<RestCall> requests) {}

    /** What a generated scenario's requests may refer to. */
    private record Fixtures(List<String> categories, List<String> tags, List<String> posts) {
        List<String> terms() {
            List<String> all = new ArrayList<>(categories);
            all.addAll(tags);
            return all;
        }
    }

    /** One query parameter: a key (bracketed for array forms) and its value, refs in braces; null value = flag. */
    private record Param(String key, String value) {}

    private static final Gen<Draft> DRAFTS = RestScenarios::draft;

    private static Draft draft(RandomGenerator random) {
        List<SetupStep> setup = new ArrayList<>();
        List<String> categories = new ArrayList<>(List.of("t:uncategorized"));
        int categoryCount = random.nextInt(4);
        for (int i = 0; i < categoryCount; i++) {
            Optional<String> parent =
                    i > 0 && random.nextBoolean() ? Optional.of("t:c" + random.nextInt(i)) : Optional.empty();
            setup.add(new SetupStep.Term("t:c" + i, "alice", "category", termName(random, "Cat", i), parent));
            categories.add("t:c" + i);
        }
        List<String> tags = new ArrayList<>();
        int tagCount = random.nextInt(4);
        for (int i = 0; i < tagCount; i++) {
            String as = pick(random, List.of("alice", "bob", "carol"));
            setup.add(new SetupStep.Term("t:g" + i, as, "post_tag", termName(random, "Tag", i), Optional.empty()));
            tags.add("t:g" + i);
        }
        List<String> posts = new ArrayList<>();
        int postCount = random.nextInt(3);
        for (int i = 0; i < postCount; i++) {
            String[] kind = pick(
                    random,
                    List.of(
                            new String[] {"carol", "publish"},
                            new String[] {"dave", "draft"},
                            new String[] {"carol", "private"},
                            new String[] {"bob", "publish"},
                            new String[] {"carol", "draft"}));
            setup.add(new SetupStep.Post("p:" + i, kind[0], "post", kind[1], "Post " + i + " " + kind[1]));
            posts.add("p:" + i);
        }
        Fixtures fixtures = new Fixtures(categories, tags, posts);
        int requestCount = 2 + random.nextInt(5);
        List<RestCall> requests = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            requests.add(request(random, fixtures));
        }
        return new Draft(setup, requests);
    }

    private static String termName(RandomGenerator random, String kind, int i) {
        String base = pick(
                random,
                List.of(
                        kind,
                        kind.toLowerCase(java.util.Locale.ROOT) + " name",
                        "Caf\u00e9 " + kind,
                        kind + " & more",
                        kind + " <b>bold</b>",
                        "  " + kind + "  spaced"));
        return base + " " + i;
    }

    private static RestCall request(RandomGenerator random, Fixtures fixtures) {
        String as = pick(random, GENERATED_CALLERS);
        String method = pick(random, List.of("GET", "GET", "GET", "GET", "HEAD", "OPTIONS", "POST", "PUT", "DELETE"));
        int route = random.nextInt(12);
        List<Param> params = new ArrayList<>();
        String path;
        switch (route) {
            case 0 -> {
                path = "/wp-json/";
                method = readOnly(random, method);
                maybe(random, params, "context", () -> pick(random, List.of("view", "embed", "edit", "bogus")));
                common(random, params);
            }
            case 1 -> {
                path = "/wp-json/wp/v2";
                method = readOnly(random, method);
                maybe(random, params, "context", () -> pick(random, List.of("view", "edit", "bogus")));
                common(random, params);
            }
            case 2 -> {
                path = "/wp-json/wp/v2/types"
                        + (random.nextBoolean()
                                ? ""
                                : "/" + pick(random, List.of("post", "page", "attachment", "wp_block", "nope")));
                method = readOnly(random, method);
                context(random, params);
                common(random, params);
            }
            case 3 -> {
                boolean item = random.nextBoolean();
                path = "/wp-json/wp/v2/taxonomies"
                        + (item ? "/" + pick(random, List.of("category", "post_tag", "nope")) : "");
                method = readOnly(random, method);
                context(random, params);
                if (!item) {
                    maybe(
                            random,
                            params,
                            "type",
                            () -> pick(random, List.of("post", "page", "attachment", "nope", "")));
                }
                common(random, params);
            }
            case 4 -> {
                path = "/wp-json/wp/v2/statuses"
                        + (random.nextBoolean()
                                ? ""
                                : "/"
                                        + pick(
                                                random,
                                                List.of(
                                                        "publish",
                                                        "draft",
                                                        "private",
                                                        "pending",
                                                        "future",
                                                        "trash",
                                                        "auto-draft",
                                                        "inherit",
                                                        "nope")));
                method = readOnly(random, method);
                context(random, params);
                common(random, params);
            }
            case 5, 6 -> {
                boolean category = route == 5;
                String base = category ? "categories" : "tags";
                List<String> own = category ? fixtures.categories() : fixtures.tags();
                if (random.nextInt(3) == 0) {
                    path = "/wp-json/wp/v2/" + base;
                    if (method.equals("GET") || method.equals("HEAD")) {
                        termListParams(random, params, fixtures, category);
                    } else if (method.equals("POST") || method.equals("PUT")) {
                        termWriteParams(random, params, fixtures, category, true);
                    }
                } else {
                    path = "/wp-json/wp/v2/" + base + "/" + termId(random, own, fixtures);
                    switch (method) {
                        case "GET", "HEAD" -> context(random, params);
                        case "POST", "PUT" -> termWriteParams(random, params, fixtures, category, false);
                        case "DELETE" ->
                            maybe(random, params, "force", () -> pick(random, List.of("true", "false", "1", "x")));
                        default -> {}
                    }
                }
                common(random, params);
            }
            case 7, 8 -> {
                if (route == 7 || method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) {
                    path = "/wp-json/wp/v2/users";
                    if (method.equals("POST") || method.equals("PUT") || method.equals("DELETE")) {
                        if (as.equals("alice")) {
                            as = pick(random, List.of("anonymous", "bob", "erin"));
                        }
                        userWriteParams(random, params);
                    } else {
                        userListParams(random, params, fixtures);
                    }
                } else {
                    // a write on one user: never by alice (it would succeed on WordPress) and never on oneself
                    if (as.equals("alice")) {
                        as = pick(random, List.of("anonymous", "bob", "carol", "dave", "erin"));
                    }
                    String caller = as;
                    String target = pick(
                            random,
                            USERS.stream().filter(u -> !u.equals(caller)).toList());
                    path = "/wp-json/wp/v2/users/" + (random.nextInt(8) == 0 ? "999999" : "{u:" + target + "}");
                    if (method.equals("DELETE")) {
                        maybe(random, params, "force", () -> pick(random, List.of("true", "false")));
                        maybe(random, params, "reassign", () -> pick(random, List.of("{u:alice}", "999999", "x")));
                    } else {
                        userWriteParams(random, params);
                    }
                }
                common(random, params);
            }
            case 9 -> {
                boolean me = random.nextInt(3) == 0;
                if (me) {
                    path = "/wp-json/wp/v2/users/me";
                    if (!(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS"))) {
                        as = "anonymous";
                    }
                } else {
                    String user = random.nextInt(8) == 0 ? "999999" : "{u:" + pick(random, USERS) + "}";
                    path = "/wp-json/wp/v2/users/" + user;
                    method = readOnly(random, method);
                }
                context(random, params);
                common(random, params);
            }
            default -> {
                path = "/wp-json/wp/v2/settings";
                if (method.equals("POST") || method.equals("PUT")) {
                    settingsParams(random, params, fixtures);
                }
                common(random, params);
            }
        }
        return new RestCall(as, method, path + query(params), Map.of(), Optional.empty(), Optional.empty());
    }

    private static String readOnly(RandomGenerator random, String method) {
        return method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")
                ? method
                : random.nextInt(4) == 0 ? method : "GET";
    }

    private static void context(RandomGenerator random, List<Param> params) {
        maybe(random, params, "context", () -> pick(random, List.of("view", "embed", "edit", "bogus")));
    }

    /** The global parameters: {@code _fields}, {@code _embed}, {@code _envelope}. */
    private static void common(RandomGenerator random, List<Param> params) {
        if (random.nextInt(4) == 0) {
            params.add(new Param(
                    "_fields",
                    pick(
                            random,
                            List.of(
                                    "id",
                                    "id,name",
                                    "name",
                                    "slug",
                                    "id,_links",
                                    "_links",
                                    "count,parent",
                                    "id,_embedded",
                                    "name.x",
                                    "title,timezone",
                                    "namespaces"))));
        }
        if (random.nextInt(5) == 0) {
            String embed = pick(random, List.of("", "up", "author", "0", "wp:term"));
            params.add(new Param("_embed", embed.isEmpty() ? null : embed));
        }
        if (random.nextInt(8) == 0) {
            params.add(new Param("_envelope", null));
        }
    }

    private static String termId(RandomGenerator random, List<String> own, Fixtures fixtures) {
        int roll = random.nextInt(10);
        if (roll == 0) {
            return "999999";
        }
        if (roll == 1 && !fixtures.terms().isEmpty()) {
            return "{" + pick(random, fixtures.terms()) + "}";
        }
        return own.isEmpty() ? "999999" : "{" + pick(random, own) + "}";
    }

    private static String idValue(RandomGenerator random, List<String> refs) {
        int roll = random.nextInt(6);
        if (roll == 0 || refs.isEmpty()) {
            return pick(random, List.of("999999", "0", "-1", "abc"));
        }
        return "{" + pick(random, refs) + "}";
    }

    private static void idList(RandomGenerator random, List<Param> params, String key, List<String> refs) {
        int form = random.nextInt(4);
        if (form == 0 && refs.size() >= 2) {
            params.add(new Param(key + "[]", "{" + refs.get(0) + "}"));
            params.add(new Param(key + "[]", "{" + refs.get(1) + "}"));
        } else if (form == 1 && refs.size() >= 2) {
            params.add(new Param(key, "{" + refs.get(refs.size() - 1) + "},{" + refs.get(0) + "}"));
        } else if (form == 2) {
            params.add(new Param(key, pick(random, List.of("", "abc", "999999", "1,x"))));
        } else {
            params.add(new Param(key, idValue(random, refs)));
        }
    }

    private static void paging(RandomGenerator random, List<Param> params, boolean offset) {
        maybe(random, params, "page", () -> pick(random, List.of("1", "2", "3", "0", "-1", "abc", "1e400")));
        maybe(random, params, "per_page", () -> pick(random, List.of("1", "2", "10", "100", "0", "101", "-5", "x")));
        if (offset) {
            maybe(random, params, "offset", () -> pick(random, List.of("0", "1", "3", "-1")));
        }
        maybe(random, params, "order", () -> pick(random, List.of("asc", "desc", "up")));
    }

    private static void termListParams(
            RandomGenerator random, List<Param> params, Fixtures fixtures, boolean category) {
        context(random, params);
        paging(random, params, !category);
        maybe(
                random,
                params,
                "orderby",
                () -> pick(random, List.of("id", "include", "name", "slug", "include_slugs", "bogus")));
        maybe(random, params, "search", () -> pick(random, List.of("", "cat", "tag", "zz", "Caf\u00e9", "a b")));
        if (random.nextInt(4) == 0) {
            idList(random, params, "include", fixtures.terms());
        }
        if (random.nextInt(5) == 0) {
            idList(random, params, "exclude", fixtures.terms());
        }
        maybe(random, params, "hide_empty", () -> pick(random, List.of("true", "false", "1", "maybe")));
        if (category) {
            maybe(random, params, "parent", () -> idValue(random, fixtures.categories()));
        }
        maybe(random, params, "post", () -> idValue(random, fixtures.posts()));
        maybe(random, params, "slug", () -> pick(random, List.of("uncategorized", "cat-0,tag-1", "nope", "")));
    }

    private static void termWriteParams(
            RandomGenerator random, List<Param> params, Fixtures fixtures, boolean category, boolean create) {
        if (!create || random.nextInt(5) != 0) {
            maybe(
                    random,
                    params,
                    "name",
                    () -> pick(random, List.of("New term", "Caf\u00e9 new", "<b>x</b>", "", "a & b", "Uncategorized")));
        }
        maybe(random, params, "description", () -> pick(random, List.of("About it", "<i>x</i>", "")));
        maybe(random, params, "slug", () -> pick(random, List.of("new-slug", "uncategorized", "Caf\u00e9", "")));
        if (category) {
            maybe(random, params, "parent", () -> idValue(random, fixtures.terms()));
        }
    }

    private static void userListParams(RandomGenerator random, List<Param> params, Fixtures fixtures) {
        context(random, params);
        paging(random, params, true);
        maybe(
                random,
                params,
                "orderby",
                () -> pick(random, List.of("id", "include", "name", "slug", "include_slugs", "email", "bogus")));
        maybe(random, params, "search", () -> pick(random, List.of("", "car", "Chen", "quarkpress", "e", "zz")));
        List<String> users = USERS.stream().map(u -> "u:" + u).toList();
        if (random.nextInt(4) == 0) {
            idList(random, params, "include", users);
        }
        if (random.nextInt(5) == 0) {
            idList(random, params, "exclude", users);
        }
        maybe(random, params, "slug", () -> pick(random, List.of("carol", "alice,bob", "nope")));
        maybe(random, params, "who", () -> pick(random, List.of("authors", "nobody")));
        maybe(random, params, "roles", () -> pick(random, List.of("administrator", "editor,author", "nope")));
        maybe(random, params, "capabilities", () -> pick(random, List.of("edit_posts", "read", "nope")));
        maybe(
                random,
                params,
                "has_published_posts",
                () -> pick(random, List.of("true", "false", "post", "post,page", "nope")));
        if (random.nextInt(6) == 0) {
            params.add(new Param("search_columns[]", pick(random, List.of("email", "name", "slug", "nope"))));
        }
    }

    private static void userWriteParams(RandomGenerator random, List<Param> params) {
        maybe(random, params, "username", () -> pick(random, List.of("newuser", "")));
        maybe(random, params, "email", () -> pick(random, List.of("new@quarkpress.test", "not-an-email")));
        maybe(random, params, "password", () -> "Gen-pass-1");
        maybe(random, params, "name", () -> pick(random, List.of("New Name", "")));
        maybe(random, params, "roles", () -> pick(random, List.of("editor", "nope")));
        maybe(random, params, "locale", () -> pick(random, List.of("en_US", "fr_FR", "")));
    }

    private static void settingsParams(RandomGenerator random, List<Param> params, Fixtures fixtures) {
        maybe(random, params, "title", () -> pick(random, List.of("New title", "A & B <i>x</i>", "", "Caf\u00e9")));
        maybe(random, params, "description", () -> pick(random, List.of("Tagline", "<b>x</b>", "")));
        maybe(
                random,
                params,
                "timezone",
                () -> pick(random, List.of("UTC", "Europe/Brussels", "Mars/Base", "UTC+2", "")));
        maybe(random, params, "date_format", () -> pick(random, List.of("Y-m-d", "<b>Y</b>", "")));
        maybe(random, params, "time_format", () -> pick(random, List.of("H:i", "g:i a")));
        maybe(random, params, "start_of_week", () -> pick(random, List.of("0", "1", "6", "-3", "9", "x")));
        maybe(random, params, "language", () -> pick(random, List.of("en_US", "fr_FR", "")));
        maybe(random, params, "use_smilies", () -> pick(random, List.of("true", "false", "0", "x")));
        maybe(random, params, "default_category", () -> idValue(random, fixtures.categories()));
        maybe(random, params, "default_post_format", () -> pick(random, List.of("0", "aside", "bogus")));
        maybe(random, params, "posts_per_page", () -> pick(random, List.of("5", "0", "-5", "abc", "1000")));
        maybe(random, params, "show_on_front", () -> pick(random, List.of("posts", "page", "x")));
        maybe(random, params, "page_on_front", () -> idValue(random, fixtures.posts()));
        maybe(random, params, "default_ping_status", () -> pick(random, List.of("open", "closed", "")));
        maybe(random, params, "default_comment_status", () -> pick(random, List.of("open", "closed", "maybe")));
        maybe(random, params, "site_logo", () -> pick(random, List.of("0", "5", "x")));
        maybe(random, params, "site_icon", () -> pick(random, List.of("0", "7", "-1")));
    }

    private static void maybe(
            RandomGenerator random, List<Param> params, String key, java.util.function.Supplier<String> value) {
        if (random.nextInt(4) == 0) {
            params.add(new Param(key, value.get()));
        }
    }

    private static <T> T pick(RandomGenerator random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }

    /** The query string: values percent-encoded as UTF-8 except refs in braces and commas. */
    private static String query(List<Param> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("?");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                out.append('&');
            }
            Param param = params.get(i);
            out.append(param.key());
            if (param.value() != null) {
                out.append('=').append(encode(param.value()));
            }
        }
        return out.toString();
    }

    private static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "-_.~,:{}".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
            }
        }
        return out.toString();
    }
}
