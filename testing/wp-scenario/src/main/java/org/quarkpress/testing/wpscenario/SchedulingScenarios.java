package org.quarkpress.testing.wpscenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.random.RandomGenerator;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.wporacle.GeneratedCases;

/**
 * The scheduling scenarios, all on the subdirectory network's main site: hand-written ones per behaviour-reference
 * rule (the publication check of a scheduled post, what a cron publication changes, the trash purge and the
 * auto-draft purge), and generated ones drawn with test-gen from fixed seeds (an editor and an author; two to four
 * posts or pages, scheduled, published, drafts or dated auto-drafts; title updates, forced dates, publication
 * checks, trash, an overdue trash time, untrash, the two purges and forced deletions; a drawn revisions limit of
 * none or zero).
 *
 * <p>The generator keeps every operation within what both sides answer alike, and tracks each post's state (live,
 * scheduled, in the trash with or without an overdue trash time, an auto-draft, gone) so that a purge's effect is
 * known: the author inserts posts only, since the format does not check capabilities and WordPress's functions
 * insert a page an author could not create through its REST layer; a date is forced only on a post still
 * scheduled; the overdue trash time is a generator choice, written only right after the trash of that post (over
 * the current time WordPress and QuarkPress wrote), so that the next trash purge is known to remove it; only a post
 * out of the trash is updated or trashed, only one in the trash is untrashed; revisions are listed only for posts
 * the generator knows survive. Two rules keep documented differences of the posts module out of the comparison
 * without hiding anything the hand-written scenarios do not already show: a post inserted as a draft is updated
 * once before it is read (a new draft's GMT modification date differs until its first update), and the auto-draft
 * purge runs before the tail while an auto-draft is left (the same difference, on a dated auto-draft). Every
 * scenario ends with the record of each post and the revisions of each surviving one, so that what the operations
 * did is compared whatever the limit.
 */
public final class SchedulingScenarios {

    static final String DIRECTORY = "scenarios/scheduling/";

    /** Generated scenarios per seed. */
    public static final int GENERATED = 20;

    static final List<String> TITLES = List.of("Alpha", "Beta", "Gamma", "Delta");

    /** The trash time the generator writes: long overdue for any trash retention. */
    static final String OVERDUE_TRASH_TIME = "1000000000";

    private static final int EDITOR = 1;
    private static final int AUTHOR = 2;

    private SchedulingScenarios() {}

    /** The hand-written scenarios listed in {@code scenarios/scheduling/index.txt}, in that order. */
    public static List<Scenario> handWritten() {
        return ScenarioIndex.load(DIRECTORY);
    }

    /** The hand-written scenarios, then the generated ones of two seeds. */
    public static List<Scenario> all() {
        List<Scenario> scenarios = new ArrayList<>(handWritten());
        scenarios.addAll(generated("20261025-scheduling-1", GENERATED));
        scenarios.addAll(generated("20261025-scheduling-2", GENERATED));
        return List.copyOf(scenarios);
    }

    /** {@code count} scenarios drawn from {@code seed}; the same seed always gives the same scenarios. */
    public static List<Scenario> generated(String seed, int count) {
        List<Drawn> drawn = GeneratedCases.sample(drawing(), seed, count);
        List<Scenario> scenarios = new ArrayList<>(drawn.size());
        String suffix = seed.substring(seed.lastIndexOf('-') + 1).toLowerCase(Locale.ROOT);
        for (int i = 0; i < drawn.size(); i++) {
            scenarios.add(new Scenario(
                    "gen-scheduling-" + suffix + "-" + i,
                    "generated",
                    NetworkVariant.SUBDIRECTORY,
                    drawn.get(i).ops(),
                    true,
                    drawn.get(i).keep()));
        }
        return scenarios;
    }

    /** One drawn scenario: its operations and its revisions limit (empty for the default). */
    record Drawn(List<ScenarioOp> ops, OptionalInt keep) {}

    /** A post the generator made, and what the generator knows of its state. */
    private static final class Post {
        static final String PUBLISH = "publish";
        static final String FUTURE = "future";
        static final String DRAFT = "draft";
        static final String AUTO_DRAFT = "auto-draft";
        static final String TRASH = "trash";
        static final String GONE = "gone";

        final int index;
        /** One of the status constants above. */
        String status;
        /** Whether its GMT date was forced into the past while scheduled. */
        boolean overdue;
        /** Whether it is in the trash with the overdue trash time. */
        boolean oldTrash;
        /** Whether it was inserted as a draft and has not been through an update or the trash since. */
        boolean freshDraft;

        Post(int index, String status) {
            this.index = index;
            this.status = status;
        }

        /** Whether an update or a trash applies to it: out of the trash, not an auto-draft, not gone. */
        boolean live() {
            return status.equals(PUBLISH) || status.equals(FUTURE) || status.equals(DRAFT);
        }

        boolean scheduled() {
            return status.equals(FUTURE);
        }

        boolean trashed() {
            return status.equals(TRASH);
        }

        boolean autoDraft() {
            return status.equals(AUTO_DRAFT);
        }

        boolean present() {
            return !status.equals(GONE);
        }
    }

    private enum Kind {
        UPDATE,
        FORCE_DATE,
        PUBLISH_CHECK,
        TRASH,
        TRASH_TIME,
        UNTRASH,
        TRASH_PURGE,
        AUTO_DRAFT_PURGE,
        DELETE
    }

    static Gen<Drawn> drawing() {
        return random -> {
            List<ScenarioOp> ops = new ArrayList<>();
            ops.add(new ScenarioOp.CreateUser("ed" + random.nextInt(100_000), "", ""));
            ops.add(new ScenarioOp.AddUser(EDITOR, 0, "editor"));
            ops.add(new ScenarioOp.CreateUser("al" + random.nextInt(100_000), "", ""));
            ops.add(new ScenarioOp.AddUser(AUTHOR, 0, "author"));
            OptionalInt keep = random.nextBoolean() ? OptionalInt.empty() : OptionalInt.of(0);

            List<Post> posts = new ArrayList<>();
            int count = 2 + random.nextInt(3);
            for (int i = 0; i < count; i++) {
                posts.add(insert(random, ops, i + 1));
            }
            int changes = 6 + random.nextInt(7);
            Kind previous = null;
            for (int i = 0; i < changes; i++) {
                previous = change(random, ops, posts, previous);
            }
            for (Post post : posts) {
                if (post.freshDraft) {
                    update(random, ops, post);
                }
            }
            if (posts.stream().anyMatch(Post::autoDraft)) {
                autoDraftPurge(ops, posts);
            }
            for (Post post : posts) {
                ops.add(new ScenarioOp.ReadPost(post.index));
            }
            for (Post post : posts) {
                if (post.present()) {
                    ops.add(new ScenarioOp.ListRevisions(
                            EDITOR, post.index, RevisionListArgs.builder().build()));
                }
            }
            return new Drawn(List.copyOf(ops), keep);
        };
    }

    /**
     * An insertion by the editor (a post or a page) or the author (a post): scheduled far ahead, published long
     * ago, a draft, or an auto-draft dated long ago.
     */
    private static Post insert(RandomGenerator random, List<ScenarioOp> ops, int index) {
        int author = random.nextBoolean() ? EDITOR : AUTHOR;
        PostFields.Builder fields =
                PostFields.builder().title("Post " + (char) ('A' + index - 1)).content("Body " + index);
        if (author == EDITOR && random.nextBoolean()) {
            fields.type("page");
        }
        int kind = random.nextInt(4);
        String status;
        if (kind == 0) {
            status = Post.FUTURE;
            fields.status("publish")
                    .date(String.format(
                            Locale.ROOT,
                            "2090-%02d-%02d %02d:00:00",
                            1 + random.nextInt(12),
                            1 + random.nextInt(28),
                            random.nextInt(24)));
        } else if (kind == 1) {
            status = Post.PUBLISH;
            fields.status("publish").date(pastDate(random, "10"));
        } else if (kind == 2) {
            status = Post.DRAFT;
            fields.status("draft");
        } else {
            status = Post.AUTO_DRAFT;
            fields.status("auto-draft").date(pastDate(random, "10"));
        }
        ops.add(new ScenarioOp.InsertPost(author, fields.build()));
        Post post = new Post(index, status);
        post.freshDraft = status.equals(Post.DRAFT);
        return post;
    }

    private static String pastDate(RandomGenerator random, String hour) {
        return String.format(
                Locale.ROOT, "2020-%02d-%02d %s:00:00", 1 + random.nextInt(12), 1 + random.nextInt(28), hour);
    }

    /** One operation among those the posts' states allow; answers its kind, for the trash-time rule. */
    private static Kind change(RandomGenerator random, List<ScenarioOp> ops, List<Post> posts, Kind previous) {
        List<Kind> kinds = new ArrayList<>();
        List<Post> live = posts.stream().filter(Post::live).toList();
        List<Post> scheduled = posts.stream().filter(Post::scheduled).toList();
        List<Post> trashed = posts.stream().filter(Post::trashed).toList();
        if (!live.isEmpty()) {
            kinds.add(Kind.UPDATE);
            kinds.add(Kind.TRASH);
        }
        if (!scheduled.isEmpty()) {
            kinds.add(Kind.FORCE_DATE);
        }
        if (previous == Kind.TRASH) {
            kinds.add(Kind.TRASH_TIME);
        }
        if (!trashed.isEmpty()) {
            kinds.add(Kind.UNTRASH);
        }
        kinds.add(Kind.PUBLISH_CHECK);
        kinds.add(Kind.TRASH_PURGE);
        kinds.add(Kind.AUTO_DRAFT_PURGE);
        kinds.add(Kind.DELETE);
        Kind kind = pick(random, kinds);
        switch (kind) {
            case UPDATE -> update(random, ops, pick(random, live));
            case FORCE_DATE -> {
                Post post = pick(random, scheduled);
                String hour = String.format(Locale.ROOT, "%02d", random.nextInt(24));
                ops.add(new ScenarioOp.ForceDate(post.index, pastDate(random, hour)));
                post.overdue = true;
            }
            case PUBLISH_CHECK -> {
                Post post = pick(random, posts);
                ops.add(new ScenarioOp.RunPublishCheck(post.index));
                if (post.scheduled() && post.overdue) {
                    post.status = Post.PUBLISH;
                }
            }
            case TRASH -> {
                Post post = pick(random, live);
                ops.add(new ScenarioOp.TrashPost(EDITOR, post.index));
                post.status = Post.TRASH;
                post.freshDraft = false;
                post.oldTrash = false;
            }
            case TRASH_TIME -> {
                // the previous operation is the trash of this post, and nothing ran since
                ScenarioOp.TrashPost trash = (ScenarioOp.TrashPost) ops.get(ops.size() - 1);
                Post post = posts.get(trash.post() - 1);
                ops.add(new ScenarioOp.UpdatePostMeta(EDITOR, post.index, "_wp_trash_meta_time", OVERDUE_TRASH_TIME));
                post.oldTrash = true;
            }
            case UNTRASH -> {
                Post post = pick(random, trashed);
                ops.add(new ScenarioOp.UntrashPost(EDITOR, post.index));
                // WordPress restores a trashed post as a draft and drops its trash time.
                post.status = Post.DRAFT;
                post.oldTrash = false;
            }
            case TRASH_PURGE -> {
                ops.add(new ScenarioOp.RunTrashPurge());
                for (Post post : posts) {
                    if (post.trashed() && post.oldTrash) {
                        post.status = Post.GONE;
                    }
                }
            }
            case AUTO_DRAFT_PURGE -> autoDraftPurge(ops, posts);
            case DELETE -> {
                Post post = pick(random, posts);
                ops.add(new ScenarioOp.DeletePost(EDITOR, post.index, true));
                post.status = Post.GONE;
                post.freshDraft = false;
            }
        }
        return kind;
    }

    /** A title update by the editor; it publishes a scheduled post whose date was forced into the past. */
    private static void update(RandomGenerator random, List<ScenarioOp> ops, Post post) {
        ops.add(new ScenarioOp.UpdatePost(
                EDITOR,
                post.index,
                PostFields.builder().title(pick(random, TITLES)).build(),
                false));
        post.freshDraft = false;
        if (post.scheduled() && post.overdue) {
            post.status = Post.PUBLISH;
        }
    }

    /** The auto-draft purge: every auto-draft the generator made is dated long ago, so none survives it. */
    private static void autoDraftPurge(List<ScenarioOp> ops, List<Post> posts) {
        ops.add(new ScenarioOp.RunAutoDraftPurge());
        for (Post post : posts) {
            if (post.autoDraft()) {
                post.status = Post.GONE;
            }
        }
    }

    private static <T> T pick(RandomGenerator random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
