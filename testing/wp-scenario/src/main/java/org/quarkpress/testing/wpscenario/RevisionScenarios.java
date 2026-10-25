package org.quarkpress.testing.wpscenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.wporacle.GeneratedCases;

/**
 * The revision scenarios, all on the subdirectory network's main site: hand-written ones per behaviour-reference rule,
 * and generated ones drawn with test-gen from fixed seeds (an editor, an author and a contributor; two to four posts;
 * updates, a footnotes write, trash, untrash and publication; autosaves; listings and lookups; a restore and a revision
 * delete; a drawn revisions limit).
 *
 * <p>The generator keeps every operation within what both sides answer alike: only the editor and a post's author
 * autosave it (a user who cannot edit the post is refused on both sides, which compares nothing), an autosave lookup
 * without a user is asked only while at most one user autosaved the post, an offset comes with a number, a user's
 * autosave of a post is saved again only while it is the latest row (see {@code Post.next}), a post inserted as a
 * draft is updated once before it is read, a revision is deleted only while no post is in the trash, and texts come
 * from a fixed list of printable ASCII words and whitespace
 * variants. Every scenario ends with the record and the revisions of each post, so that what the operations did is
 * compared whatever the limit.
 */
public final class RevisionScenarios {

    static final String DIRECTORY = "scenarios/revisions/";

    /** Generated scenarios per seed. */
    public static final int GENERATED = 20;

    /** Texts: words and whitespace variants of them (a trailing space, a doubled space, a newline, a tab). */
    static final List<String> CONTENTS =
            List.of("one", "one ", "one  two", "one\ntwo", "one\n two", "one\ttwo", "two", "three");

    /** Revisioned meta values: canonical footnotes only, since WordPress sanitizes anything else to an empty list. */
    static final List<String> FOOTNOTES =
            List.of("[]", "[{\"id\":\"a1\",\"content\":\"One\"}]", "[{\"id\":\"a2\",\"content\":\"Two\"}]");

    static final List<String> TITLES = List.of("Alpha", "Beta", "Gamma", "Delta");

    private static final int EDITOR = 1;
    private static final int AUTHOR = 2;

    private RevisionScenarios() {}

    /** The hand-written scenarios listed in {@code scenarios/revisions/index.txt}, in that order. */
    public static List<Scenario> handWritten() {
        return ScenarioIndex.load(DIRECTORY);
    }

    /** The hand-written scenarios, then the generated ones of two seeds. */
    public static List<Scenario> all() {
        List<Scenario> scenarios = new ArrayList<>(handWritten());
        scenarios.addAll(generated("20261022-revisions-1", GENERATED));
        scenarios.addAll(generated("20261022-revisions-2", GENERATED));
        return List.copyOf(scenarios);
    }

    /** {@code count} scenarios drawn from {@code seed}; the same seed always gives the same scenarios. */
    public static List<Scenario> generated(String seed, int count) {
        List<Drawn> drawn = GeneratedCases.sample(drawing(), seed, count);
        List<Scenario> scenarios = new ArrayList<>(drawn.size());
        String suffix = seed.substring(seed.lastIndexOf('-') + 1).toLowerCase(Locale.ROOT);
        for (int i = 0; i < drawn.size(); i++) {
            scenarios.add(new Scenario(
                    "gen-revisions-" + suffix + "-" + i,
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

    /** A post the generator made. */
    private static final class Post {
        final int index;
        final int author;
        String status;
        String title;
        String content;
        final Set<Integer> autosavers = new TreeSet<>();
        /** Whether it was inserted as a draft and has not been through an update since. */
        boolean freshDraft;

        int lastAutosaver;

        Post(int index, int author, String status, String title, String content) {
            this.index = index;
            this.author = author;
            this.status = status;
            this.title = title;
            this.content = content;
        }

        boolean live() {
            return !status.equals("trash");
        }

        /**
         * The users who may autosave the post next: those who may edit it, except one whose autosave is not the
         * latest row of the post. Each side dates a re-saved autosave with its own clock, in a separate run, so a
         * re-save that passes a newer row could tie with it in one run and not in the other; this keeps the listing
         * order independent of second boundaries. It does not hide a difference.
         */
        List<Integer> next() {
            return editors().stream()
                    .filter(user -> !autosavers.contains(user) || user == lastAutosaver)
                    .toList();
        }

        /** The users who may edit the post, and so autosave it. */
        List<Integer> editors() {
            return author == EDITOR ? List.of(EDITOR) : List.of(EDITOR, AUTHOR);
        }
    }

    private static Gen<Drawn> drawing() {
        return random -> {
            List<ScenarioOp> ops = new ArrayList<>();
            String[] names = {"ed", "au", "co"};
            String[] roles = {"editor", "author", "contributor"};
            for (int i = 0; i < names.length; i++) {
                ops.add(new ScenarioOp.CreateUser(names[i] + random.nextInt(100_000), "", ""));
                ops.add(new ScenarioOp.AddUser(i + 1, 0, roles[i]));
            }
            int slot = random.nextInt(7);
            OptionalInt keep = slot < 3 ? OptionalInt.empty() : OptionalInt.of(slot - 3);

            List<Post> posts = new ArrayList<>();
            int count = 2 + random.nextInt(3);
            for (int i = 0; i < count; i++) {
                posts.add(insert(random, ops, i + 1));
            }
            int changes = 2 + random.nextInt(5);
            int version = 0;
            for (int i = 0; i < changes; i++) {
                if (i < 2) {
                    version = update(random, ops, posts, version);
                    continue;
                }
                version = change(random, ops, posts, version);
            }
            for (Post post : posts) {
                if (post.freshDraft) {
                    // A draft's modification date is its zero GMT date until its first update.
                    ops.add(new ScenarioOp.UpdatePost(
                            EDITOR,
                            post.index,
                            PostFields.builder()
                                    .menuOrder(1 + random.nextInt(5))
                                    .build(),
                            false));
                    post.freshDraft = false;
                }
            }
            int autosaves = 2 + random.nextInt(7);
            for (int i = 0; i < autosaves; i++) {
                autosave(random, ops, posts);
            }
            int reads = 2 + random.nextInt(3);
            for (int i = 0; i < reads; i++) {
                Post post = pick(random, posts);
                ops.add(new ScenarioOp.ListRevisions(reader(random, post), post.index, listArgs(random)));
            }
            for (Post post : posts) {
                lookups(random, ops, post);
            }
            if (random.nextInt(3) != 0) {
                List<String> fields = new ArrayList<>();
                if (random.nextBoolean()) {
                    fields.add(pick(random, ScenarioRules.REVISION_FIELDS));
                }
                ops.add(new ScenarioOp.Restore(EDITOR, "r" + (1 + random.nextInt(4)), fields));
            }
            if (random.nextInt(3) == 0 && posts.stream().allMatch(Post::live)) {
                ops.add(new ScenarioOp.DeleteRevision("r" + (1 + random.nextInt(4))));
            }
            for (Post post : posts) {
                ops.add(new ScenarioOp.ReadPost(post.index));
            }
            for (Post post : posts) {
                ops.add(new ScenarioOp.ListRevisions(
                        EDITOR,
                        post.index,
                        RevisionListArgs.builder().checkEnabled(false).build()));
            }
            return new Drawn(List.copyOf(ops), keep);
        };
    }

    /** An insertion by the editor or the author: published (dated long ago), a draft, or scheduled far ahead. */
    private static Post insert(RandomGenerator random, List<ScenarioOp> ops, int index) {
        int author = random.nextBoolean() ? EDITOR : AUTHOR;
        int kind = random.nextInt(6);
        String status = kind < 3 ? "publish" : kind < 5 ? "draft" : "future";
        String title = "Post " + (char) ('A' + index - 1);
        String content = pick(random, CONTENTS);
        PostFields.Builder fields =
                PostFields.builder().title(title).content(content).status(status);
        if (status.equals("publish")) {
            fields.date(String.format(Locale.ROOT, "2020-01-%02d 10:00:00", index));
        } else if (status.equals("future")) {
            fields.date(String.format(Locale.ROOT, "2090-01-%02d 10:00:00", index));
        }
        ops.add(new ScenarioOp.InsertPost(author, fields.build()));
        Post post = new Post(index, author, status, title, content);
        post.freshDraft = status.equals("draft");
        return post;
    }

    /** One of the operations after the first two updates; falls back to an update when its post is missing. */
    private static int change(RandomGenerator random, List<ScenarioOp> ops, List<Post> posts, int version) {
        int kind = random.nextInt(8);
        if (kind < 3) {
            return update(random, ops, posts, version);
        }
        if (kind == 3) {
            Post post = pick(random, posts);
            ops.add(new ScenarioOp.UpdatePostMeta(
                    pick(random, post.editors()), post.index, "footnotes", pick(random, FOOTNOTES)));
            return version;
        }
        if (kind == 4 || kind == 5) {
            List<Post> published =
                    posts.stream().filter(post -> post.status.equals("publish")).toList();
            if (!published.isEmpty()) {
                Post post = pick(random, published);
                ops.add(new ScenarioOp.TrashPost(EDITOR, post.index));
                post.status = "trash";
                post.freshDraft = false;
                return version;
            }
            List<Post> trashed =
                    posts.stream().filter(post -> post.status.equals("trash")).toList();
            if (!trashed.isEmpty()) {
                return untrash(random, ops, trashed, version);
            }
            return update(random, ops, posts, version);
        }
        if (kind == 6) {
            List<Post> trashed =
                    posts.stream().filter(post -> post.status.equals("trash")).toList();
            return trashed.isEmpty() ? update(random, ops, posts, version) : untrash(random, ops, trashed, version);
        }
        List<Post> waiting = posts.stream()
                .filter(post -> post.status.equals("draft") || post.status.equals("future"))
                .toList();
        if (waiting.isEmpty()) {
            return update(random, ops, posts, version);
        }
        Post post = pick(random, waiting);
        ops.add(new ScenarioOp.PublishPost(post.index));
        post.status = "publish";
        return version;
    }

    private static int untrash(RandomGenerator random, List<ScenarioOp> ops, List<Post> trashed, int version) {
        Post post = pick(random, trashed);
        ops.add(new ScenarioOp.UntrashPost(EDITOR, post.index));
        post.freshDraft = false;
        // WordPress restores a trashed post as a draft.
        post.status = "draft";
        return version;
    }

    /** An update of a post: a title, a content (sometimes the one it has), or a slug. */
    private static int update(RandomGenerator random, List<ScenarioOp> ops, List<Post> posts, int version) {
        Post post = pick(random, posts);
        int as = pick(random, post.editors());
        post.freshDraft = false;
        PostFields.Builder fields = PostFields.builder();
        int kind = random.nextInt(6);
        int next = version;
        if (kind < 2) {
            next++;
            post.title = "v" + next;
            fields.title(post.title);
        } else if (kind < 5) {
            post.content = random.nextInt(4) == 0 ? post.content : pick(random, CONTENTS);
            fields.content(post.content);
        } else if (random.nextBoolean()) {
            fields.menuOrder(1 + random.nextInt(5));
        } else {
            next++;
            fields.slug("slug-" + next);
        }
        ops.add(new ScenarioOp.UpdatePost(as, post.index, fields.build(), false));
        return next;
    }

    /** An autosave of a post by the editor or the post's author, with a field subset. */
    private static void autosave(RandomGenerator random, List<ScenarioOp> ops, List<Post> posts) {
        Post post = pick(random, posts);
        int as = pick(random, post.next());
        AutosaveFields.Builder fields = AutosaveFields.builder();
        boolean title = random.nextBoolean();
        boolean content = random.nextBoolean();
        boolean excerpt = random.nextInt(4) == 0;
        if (!title && !content && !excerpt) {
            content = true;
        }
        if (title) {
            fields.title(pick(random, TITLES));
        }
        if (content) {
            fields.content(random.nextInt(4) == 0 ? post.content : pick(random, CONTENTS));
        }
        if (excerpt) {
            fields.excerpt(pick(random, CONTENTS));
        }
        Optional<Map<String, String>> meta = Optional.empty();
        int kind = random.nextInt(5);
        if (kind == 0) {
            meta = Optional.of(Map.of("footnotes", pick(random, FOOTNOTES)));
        } else if (kind == 1) {
            meta = Optional.of(Map.of());
        }
        ops.add(new ScenarioOp.Autosave(as, post.index, fields.build(), meta));
        post.autosavers.add(as);
        post.lastAutosaver = as;
    }

    /** The user who lists a post's revisions: the editor, or the post's author. */
    private static int reader(RandomGenerator random, Post post) {
        return pick(random, post.editors());
    }

    private static RevisionListArgs listArgs(RandomGenerator random) {
        RevisionListArgs.Builder args = RevisionListArgs.builder();
        if (random.nextBoolean()) {
            args.order(pick(random, List.of("asc", "desc")));
        }
        if (random.nextBoolean()) {
            args.number(1 + random.nextInt(3));
            if (random.nextBoolean()) {
                args.offset(random.nextInt(3));
            }
        }
        if (random.nextInt(3) == 0) {
            args.author(1 + random.nextInt(3));
        }
        if (random.nextInt(3) == 0) {
            args.autosaves(pick(random, ScenarioRules.AUTOSAVE_MODES));
        }
        if (random.nextInt(4) == 0) {
            args.checkEnabled(false);
        }
        return args.build();
    }

    /** An autosave lookup by a user who autosaved the post (any user only while at most one did), and a count. */
    private static void lookups(RandomGenerator random, List<ScenarioOp> ops, Post post) {
        List<Integer> who = new ArrayList<>(post.autosavers);
        if (post.autosavers.size() <= 1) {
            who.add(0);
        }
        ops.add(new ScenarioOp.AutosaveOf(EDITOR, post.index, pick(random, who)));
        ops.add(new ScenarioOp.CountRevisions(EDITOR, post.index));
    }

    private static <T> T pick(RandomGenerator random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
