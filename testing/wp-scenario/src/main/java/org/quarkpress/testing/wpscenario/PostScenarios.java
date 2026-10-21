package org.quarkpress.testing.wpscenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.wporacle.GeneratedCases;

/**
 * The post scenarios, all on the subdirectory network's main site: hand-written ones per behaviour-reference rule,
 * and generated ones drawn with test-gen from fixed seeds (an editor and an author, sometimes a time zone, six to
 * twelve inserts drawn from small vocabularies chosen to collide, a few updates, trash, untrash, delete and stick
 * operations, then queries, page URIs and a read).
 */
public final class PostScenarios {

    static final String DIRECTORY = "scenarios/posts/";

    /** Generated scenarios per seed. */
    public static final int GENERATED = 20;

    private static final String E_ACUTE = Character.toString(0xE9);

    static final List<String> TITLES = List.of(
            "Hello World",
            "hello world",
            "Hello  World",
            "Caf" + E_ACUTE,
            "Cafe",
            Character.toString(0xFC) + "ber",
            "100% sure",
            "a_b",
            "<b>Bold</b> move",
            "Tom &amp; Jerry",
            "page",
            "2020",
            "--",
            "",
            "Red car",
            "red bike",
            Character.toString(0x65E5) + Character.toString(0x672C),
            "x");

    static final List<String> SLUGS =
            List.of("hello-world", "cafe", "caf%c3%a9", "page", "2020", "x", "", "Hello World");

    static final List<String> CONTENTS = List.of(
            "",
            "plain",
            "a red car",
            "<p>para</p>",
            "<script>x()</script>ok",
            "<em onclick=\"x\">e</em>",
            "<!-- wp:paragraph --><p>b</p><!-- /wp:paragraph -->",
            "&copy; &bogus;");

    static final List<String> STATUSES = List.of("publish", "publish", "draft", "pending", "private", "future", "");

    /** Valid zones only: an unknown zone is refused by WordPress and stored by the runner. */
    static final List<String> ZONES = List.of("UTC", "Europe/Paris", "America/New_York", "Asia/Kolkata");

    static final List<String> SEARCHES =
            List.of("red", "red car", "hello -world", "\"hello world\"", "caf" + E_ACUTE, "100%");

    static final List<String> ORDERS = List.of(
            "date desc",
            "date asc",
            "title asc",
            "title desc",
            "name asc",
            "menu_order asc",
            "id desc",
            "modified desc",
            "author asc",
            "parent asc",
            "type asc");

    private PostScenarios() {}

    /** The hand-written scenarios listed in {@code scenarios/posts/index.txt}, in that order. */
    public static List<Scenario> handWritten() {
        return ScenarioIndex.load(DIRECTORY);
    }

    /** The hand-written scenarios, then the generated ones of two seeds. */
    public static List<Scenario> all() {
        List<Scenario> scenarios = new ArrayList<>(handWritten());
        scenarios.addAll(generated("20261020-posts-1", GENERATED));
        scenarios.addAll(generated("20261020-posts-2", GENERATED));
        return List.copyOf(scenarios);
    }

    /** {@code count} scenarios drawn from {@code seed}; the same seed always gives the same scenarios. */
    public static List<Scenario> generated(String seed, int count) {
        List<List<ScenarioOp>> drawn = GeneratedCases.sample(ops(), seed, count);
        List<Scenario> scenarios = new ArrayList<>(drawn.size());
        String suffix = seed.substring(seed.lastIndexOf('-') + 1).toLowerCase(Locale.ROOT);
        for (int i = 0; i < drawn.size(); i++) {
            scenarios.add(new Scenario(
                    "gen-posts-" + suffix + "-" + i, "generated", NetworkVariant.SUBDIRECTORY, drawn.get(i), true));
        }
        return scenarios;
    }

    /**
     * One scenario's operations. Logins carry a drawn number so that scenarios rarely share one, and the {@code ed}
     * and {@code au} prefixes keep a scenario's two users apart and away from {@code admin}; writes act as the editor
     * or user 0, queries as anyone.
     */
    static Gen<List<ScenarioOp>> ops() {
        return random -> {
            List<ScenarioOp> ops = new ArrayList<>();
            ops.add(new ScenarioOp.CreateUser("ed" + random.nextInt(100_000), "", ""));
            ops.add(new ScenarioOp.AddUser(1, 0, "editor"));
            ops.add(new ScenarioOp.CreateUser("au" + random.nextInt(100_000), "", ""));
            ops.add(new ScenarioOp.AddUser(2, 0, "author"));
            if (random.nextBoolean()) {
                ops.add(new ScenarioOp.SiteOption("timezone_string", pick(random, ZONES)));
            }
            int inserts = 6 + random.nextInt(7);
            List<Boolean> stickable = new ArrayList<>();
            List<Boolean> pages = new ArrayList<>();
            for (int i = 1; i <= inserts; i++) {
                boolean page = random.nextInt(3) == 0;
                boolean password = random.nextInt(8) == 0;
                PostFields.Builder fields = PostFields.builder().title(pick(random, TITLES));
                if (page) {
                    fields.type("page");
                    if (i > 1 && random.nextBoolean()) {
                        int parent = 1 + random.nextInt(i - 1);
                        if (pages.get(parent - 1)) {
                            fields.parent(parent);
                        }
                    }
                }
                String status = pick(random, STATUSES);
                if (!status.isEmpty()) {
                    fields.status(status);
                }
                if (random.nextInt(4) != 0) {
                    fields.date(date(random));
                }
                if (random.nextInt(3) == 0) {
                    fields.slug(pick(random, SLUGS));
                }
                if (random.nextBoolean()) {
                    fields.content(pick(random, CONTENTS));
                }
                if (password) {
                    fields.password("pw");
                }
                if (random.nextInt(4) == 0) {
                    fields.menuOrder(random.nextInt(4));
                }
                if (random.nextInt(4) == 0) {
                    fields.author(1 + random.nextInt(2));
                }
                ops.add(new ScenarioOp.InsertPost(random.nextInt(3) == 0 ? 0 : 1, fields.build()));
                pages.add(page);
                stickable.add(!page && !password);
            }
            int changes = random.nextInt(6);
            Trash trash = new Trash();
            for (int i = 0; i < changes; i++) {
                ScenarioOp change = change(random, inserts, stickable, trash);
                trash.follow(change);
                ops.add(change);
            }
            int queries = 3 + random.nextInt(4);
            for (int i = 0; i < queries; i++) {
                int as = random.nextInt(3);
                ops.add(new ScenarioOp.QueryPosts(as, queryArgs(random, inserts, as)));
            }
            for (int i = 0; i < inserts; i++) {
                if (pages.get(i) && random.nextBoolean()) {
                    ops.add(new ScenarioOp.PageUri(i + 1));
                }
            }
            ops.add(new ScenarioOp.ReadPost(1 + random.nextInt(inserts)));
            return ops;
        };
    }

    /**
     * An update, trash, untrash, delete, stick or publish of a drawn post, as the editor or user 0. An untrash, and
     * a delete of an even-numbered draw, act on the post trashed last when there is one, so the restore and
     * delete-from-trash paths are reached; the draws stay the same either way.
     */
    private static ScenarioOp change(RandomGenerator random, int inserts, List<Boolean> stickable, Trash trash) {
        int post = 1 + random.nextInt(inserts);
        int as = random.nextInt(3) == 0 ? 0 : 1;
        return switch (random.nextInt(7)) {
            case 0 ->
                new ScenarioOp.UpdatePost(
                        as,
                        post,
                        PostFields.builder().title(pick(random, TITLES)).build(),
                        false);
            case 1 ->
                new ScenarioOp.UpdatePost(
                        as, post, PostFields.builder().slug(pick(random, SLUGS)).build(), false);
            case 2 -> {
                String status = pick(random, List.of("publish", "draft", "private"));
                yield new ScenarioOp.UpdatePost(
                        as, post, PostFields.builder().status(status).build(), random.nextBoolean());
            }
            case 3 -> new ScenarioOp.TrashPost(as, post);
            case 4 -> new ScenarioOp.UntrashPost(as, trash.last().orElse(post));
            case 5 ->
                new ScenarioOp.DeletePost(as, post % 2 == 0 ? trash.last().orElse(post) : post, random.nextBoolean());
            default -> stickable.get(post - 1) ? new ScenarioOp.Stick(post, true) : new ScenarioOp.PublishPost(post);
        };
    }

    /** The posts a scenario's changes have put in the trash so far, and those they deleted. */
    private static final class Trash {
        private final List<Integer> trashed = new ArrayList<>();
        private final List<Integer> deleted = new ArrayList<>();

        Optional<Integer> last() {
            return trashed.isEmpty() ? Optional.empty() : Optional.of(trashed.getLast());
        }

        void follow(ScenarioOp change) {
            switch (change) {
                case ScenarioOp.TrashPost trash -> put(trash.post());
                case ScenarioOp.UntrashPost untrash -> trashed.remove(Integer.valueOf(untrash.post()));
                case ScenarioOp.UpdatePost update
                when update.fields().status().isPresent() -> trashed.remove(Integer.valueOf(update.post()));
                case ScenarioOp.DeletePost delete -> {
                    if (delete.force() || trashed.contains(delete.post())) {
                        trashed.remove(Integer.valueOf(delete.post()));
                        deleted.add(delete.post());
                    } else {
                        put(delete.post());
                    }
                }
                default -> {}
            }
        }

        private void put(int post) {
            if (!deleted.contains(post) && !trashed.contains(post)) {
                trashed.add(post);
            }
        }
    }

    /**
     * A query's arguments. The author (user 2) names statuses only for posts: WordPress's REST layer refuses an
     * author a status filter on pages.
     */
    private static QueryArgs queryArgs(RandomGenerator random, int inserts, int as) {
        QueryArgs.Builder args = QueryArgs.builder();
        int types = random.nextInt(4);
        boolean statuses = random.nextInt(3) == 0;
        if (statuses && as == 2) {
            args.types("post");
        } else {
            switch (types) {
                case 0 -> args.types("page");
                case 1 -> args.types("post", "page");
                case 2 -> args.types("any");
                default -> {}
            }
        }
        if (statuses) {
            args.statuses(pick(random, List.of("publish", "any", "draft", "private")));
        }
        if (random.nextInt(4) == 0) {
            args.search(pick(random, SEARCHES));
        }
        if (random.nextInt(4) == 0) {
            args.authorIn(1 + random.nextInt(2));
        }
        if (random.nextInt(5) == 0) {
            args.postNotIn(1 + random.nextInt(inserts));
        }
        if (random.nextInt(5) == 0) {
            args.postIn(1 + random.nextInt(inserts), 1 + random.nextInt(inserts));
        }
        if (random.nextInt(5) == 0) {
            args.parent(0);
        }
        if (random.nextBoolean()) {
            args.orderBy(pick(random, ORDERS));
        }
        args.perPage(pick(random, List.of(-1, 3, 5, 10)));
        if (random.nextInt(3) == 0) {
            args.page(1 + random.nextInt(3));
        }
        args.stickyFirst(random.nextBoolean());
        return args.build();
    }

    /** A date far from the run: 2000-2019 or 2090-2099, with any time of day. */
    private static String date(RandomGenerator random) {
        int year = random.nextBoolean() ? 2000 + random.nextInt(20) : 2090 + random.nextInt(10);
        return String.format(
                Locale.ROOT,
                "%04d-%02d-%02d %02d:%02d:%02d",
                year,
                1 + random.nextInt(12),
                1 + random.nextInt(28),
                random.nextInt(24),
                random.nextInt(60),
                random.nextInt(60));
    }

    private static <T> T pick(RandomGenerator random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
