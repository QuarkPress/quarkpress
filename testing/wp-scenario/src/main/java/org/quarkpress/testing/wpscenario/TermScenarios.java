package org.quarkpress.testing.wpscenario;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.wporacle.GeneratedCases;

/**
 * The term scenarios, all on the subdirectory network's main site: hand-written ones per behaviour-reference rule, and
 * generated ones drawn with test-gen from fixed seeds (an editor, three to five posts, four to eight terms of the three
 * taxonomies, assignments, a rename, slug change or re-parenting, post status changes, a post deleted, a term deleted,
 * then reads, lookups and queries).
 *
 * <p>The generator follows the terms and posts it has made, so that each operation reaches the state it is drawn to
 * test: a status change moves a post into or out of publish, a trash, untrash or delete names a post in the state it
 * leaves, a removal names a term the post has, a term delete prefers a term with children or posts, a re-parenting
 * changes the parent, a slug change takes another term's slug or regenerates a given one, an absent id names a deleted
 * term, and a query's parent, child_of or exclude_tree names a term with children. Every scenario ends with the terms
 * of each remaining post in every taxonomy, so that what the operations did to unpublished posts is compared too.
 */
public final class TermScenarios {

    static final String DIRECTORY = "scenarios/terms/";

    /** Generated scenarios per seed. */
    public static final int GENERATED = 20;

    /**
     * Names, never another's slug with a suffix. Letters, with a quote in two of them (stored as given, and matched in
     * lookups and LIKE) placed where the quote never decides the order against another character than a letter: name
     * order is then the same under WordPress's collation and code points.
     */
    static final List<String> NAMES = List.of(
            "Alpha",
            "beta",
            "Gamma",
            "delta",
            "Epsilon",
            "zeta",
            "Eta",
            "theta",
            "Iota",
            "kappa",
            "Lambda",
            "omega",
            "News",
            "world",
            "Sport",
            "travel",
            "Music",
            "books",
            "O'Hara",
            "Say \"Hi\"");

    /** Given slugs: they collide with each other on purpose (the suffix rules), never with a name's slug. */
    static final List<String> SLUGS = List.of("s-one", "s-two", "s-one", "");

    /**
     * Names an assignment uses for a term it creates: no other term name or slug starts with "fresh" or a digit. A
     * numeric string is a name, not an id.
     */
    static final List<String> FRESH = List.of("Fresh A", "Fresh B", "Fresh O'K", "Fresh D", "1005", "2077");

    static final List<String> DESCRIPTIONS = List.of("About it", "Says \"hi\" & 'bye'", "<em>kept</em> 'x'");

    static final List<String> TAXONOMIES = List.of("category", "post_tag", "genre");

    static final List<String> ORDERS = List.of("name", "count", "id", "slug", "none", "include", "term_group", "rand");

    static final List<String> DIRECTIONS = List.of("ASC", "asc", "DESC", "", "up");

    private TermScenarios() {}

    /** The hand-written scenarios listed in {@code scenarios/terms/index.txt}, in that order. */
    public static List<Scenario> handWritten() {
        return ScenarioIndex.load(DIRECTORY);
    }

    /** The hand-written scenarios, then the generated ones of two seeds. */
    public static List<Scenario> all() {
        List<Scenario> scenarios = new ArrayList<>(handWritten());
        scenarios.addAll(generated("20261021-terms-1", GENERATED));
        scenarios.addAll(generated("20261021-terms-2", GENERATED));
        return List.copyOf(scenarios);
    }

    /** {@code count} scenarios drawn from {@code seed}; the same seed always gives the same scenarios. */
    public static List<Scenario> generated(String seed, int count) {
        List<List<ScenarioOp>> drawn = GeneratedCases.sample(ops(), seed, count);
        List<Scenario> scenarios = new ArrayList<>(drawn.size());
        String suffix = seed.substring(seed.lastIndexOf('-') + 1).toLowerCase(Locale.ROOT);
        for (int i = 0; i < drawn.size(); i++) {
            scenarios.add(new Scenario(
                    "gen-terms-" + suffix + "-" + i, "generated", NetworkVariant.SUBDIRECTORY, drawn.get(i), true));
        }
        return scenarios;
    }

    /** A term the generator made: {@code t<k>}. */
    private static final class Term {
        final int k;
        final String taxonomy;
        String name;
        /** The slug, when the generator knows it: derived from a name, not given (a given one may get a suffix). */
        Optional<String> slug;

        boolean described;
        int parent;
        boolean live = true;

        Term(int k, String taxonomy, String name, boolean givenSlug, int parent) {
            this.k = k;
            this.taxonomy = taxonomy;
            this.name = name;
            this.slug = givenSlug ? Optional.empty() : Optional.of(slugOf(name));
            this.parent = parent;
        }

        String ref() {
            return "t" + k;
        }
    }

    /** A post the generator made: its status, and the terms ({@code k}, 0 for the default) it has per taxonomy. */
    private static final class Post {
        final int index;
        String status;
        boolean deleted;
        final List<Set<Integer>> terms = List.of(new HashSet<>(), new HashSet<>(), new HashSet<>());

        Post(int index, String status) {
            this.index = index;
            this.status = status;
        }

        Set<Integer> of(String taxonomy) {
            return terms.get(TAXONOMIES.indexOf(taxonomy));
        }
    }

    /** What the generator knows of the terms and posts made so far. */
    private static final class Known {
        final List<Term> terms = new ArrayList<>();
        final List<Post> posts = new ArrayList<>();
        final Set<String> used = new HashSet<>();

        Term created(String taxonomy, String name, boolean givenSlug, int parent) {
            Term term = new Term(terms.size() + 1, taxonomy, name, givenSlug, parent);
            terms.add(term);
            return term;
        }

        List<Term> live(String taxonomy) {
            return terms.stream()
                    .filter(term -> term.live && term.taxonomy.equals(taxonomy))
                    .toList();
        }

        Optional<Term> named(String taxonomy, String name) {
            String lower = ScenarioRules.lowerAscii(name);
            return live(taxonomy).stream()
                    .filter(term -> ScenarioRules.lowerAscii(term.name).equals(lower))
                    .findFirst();
        }

        boolean hasChildren(Term term) {
            return terms.stream().anyMatch(other -> other.live && other.parent == term.k);
        }

        boolean assigned(Term term) {
            return posts.stream()
                    .anyMatch(post -> !post.deleted && post.of(term.taxonomy).contains(term.k));
        }

        /** Whether the term is the only one of its taxonomy on a post still there. */
        boolean sole(Term term) {
            return posts.stream()
                    .anyMatch(post -> !post.deleted && post.of(term.taxonomy).equals(Set.of(term.k)));
        }

        /** Whether the taxonomy has a child term on a published post. */
        boolean countedChild(String taxonomy) {
            return live(taxonomy).stream()
                    .anyMatch(term -> term.parent != 0
                            && posts.stream()
                                    .anyMatch(post -> !post.deleted
                                            && post.status.equals("publish")
                                            && post.of(taxonomy).contains(term.k)));
        }

        boolean descends(Term term, int ancestor) {
            for (int parent = term.parent; parent != 0; parent = terms.get(parent - 1).parent) {
                if (parent == ancestor) {
                    return true;
                }
            }
            return false;
        }

        List<Post> present() {
            return posts.stream().filter(post -> !post.deleted).toList();
        }

        void delete(Term term) {
            term.live = false;
            for (Term other : terms) {
                if (other.live && other.parent == term.k) {
                    other.parent = term.parent;
                }
            }
            for (Post post : posts) {
                Set<Integer> set = post.of(term.taxonomy);
                if (set.remove(term.k) && set.isEmpty() && !term.taxonomy.equals("post_tag")) {
                    set.add(0);
                }
            }
        }
    }

    static Gen<List<ScenarioOp>> ops() {
        return random -> {
            List<ScenarioOp> ops = new ArrayList<>();
            Known known = new Known();
            ops.add(new ScenarioOp.CreateUser("te" + random.nextInt(100_000), "", ""));
            ops.add(new ScenarioOp.AddUser(1, 0, "editor"));
            int posts = 3 + random.nextInt(3);
            for (int i = 0; i < posts; i++) {
                String status = pick(random, List.of("publish", "publish", "draft", "private"));
                PostFields.Builder fields =
                        PostFields.builder().title("Post " + (char) ('A' + i)).status(status);
                if (!status.equals("draft")) {
                    fields.date(String.format(Locale.ROOT, "2019-01-%02d 10:00:00", 1 + i));
                }
                ops.add(new ScenarioOp.InsertPost(1, fields.build()));
                Post post = new Post(i + 1, status);
                post.of("category").add(0);
                post.of("genre").add(0);
                known.posts.add(post);
            }
            int inserts = 4 + random.nextInt(5);
            for (int i = 0; i < inserts; i++) {
                ops.add(insert(random, known));
            }
            int assignments = 2 + random.nextInt(4);
            for (int i = 0; i < assignments; i++) {
                ops.add(assign(random, known));
            }
            int updates = 1 + random.nextInt(2);
            for (int i = 0; i < updates; i++) {
                update(random, known).ifPresent(ops::add);
            }
            if (random.nextInt(3) == 0) {
                Post post = preferred(random, known.present(), candidate -> candidate.status.equals("draft"));
                if (post != null) {
                    String date = String.format(Locale.ROOT, "2019-02-%02d 10:00:00", post.index);
                    ops.add(new ScenarioOp.UpdatePost(
                            1,
                            post.index,
                            PostFields.builder().status("publish").date(date).build(),
                            true));
                    post.status = "publish";
                }
            }
            if (random.nextInt(3) == 0) {
                unpublish(random, known).ifPresent(ops::add);
            }
            if (random.nextInt(4) == 0) {
                Post post = preferred(
                        random,
                        known.present(),
                        candidate -> candidate.status.equals("draft") || candidate.status.equals("private"));
                if (post != null) {
                    ops.add(new ScenarioOp.PublishPost(post.index));
                    post.status = "publish";
                }
            }
            if (random.nextInt(3) == 0) {
                Post post = preferred(random, known.present(), candidate -> !candidate.status.equals("trash"));
                if (post != null) {
                    ops.add(new ScenarioOp.TrashPost(1, post.index));
                    post.status = "trash";
                }
            }
            if (random.nextBoolean()) {
                Post post = preferred(random, known.present(), candidate -> candidate.status.equals("trash"));
                if (post != null) {
                    // WordPress restores a trashed post as a draft.
                    ops.add(new ScenarioOp.UntrashPost(1, post.index));
                    post.status = "draft";
                }
            }
            if (random.nextInt(4) == 0) {
                Post post = preferred(random, known.present(), candidate -> true);
                ops.add(new ScenarioOp.DeletePost(1, post.index, true));
                post.deleted = true;
            }
            if (random.nextBoolean()) {
                removeTerms(random, known).ifPresent(ops::add);
            }
            if (random.nextInt(4) != 0) {
                Optional<Term> deleted = deleteTerm(random, known);
                if (deleted.isPresent()) {
                    ops.add(new ScenarioOp.DeleteTerm(1, deleted.get().ref(), deleted.get().taxonomy));
                    if (random.nextBoolean()) {
                        ops.add(absentAssign(random, known, deleted.get()));
                    }
                }
            }
            int lookups = 1 + random.nextInt(2);
            for (int i = 0; i < lookups; i++) {
                ops.add(getTerm(random, known));
            }
            for (int i = 0; i < 2; i++) {
                ops.add(new ScenarioOp.PostTerms(pick(random, known.present()).index, pick(random, TAXONOMIES)));
            }
            ops.add(exists(random, known));
            int queries = 3 + random.nextInt(3);
            for (int i = 0; i < queries; i++) {
                ops.add(new ScenarioOp.QueryTerms(random.nextInt(2), queryArgs(random, known)));
            }
            for (Post post : known.present()) {
                for (String taxonomy : TAXONOMIES) {
                    ops.add(new ScenarioOp.PostTerms(post.index, taxonomy));
                }
            }
            return ops;
        };
    }

    /** An insert with a name used nowhere yet; a parent of the same taxonomy only in a hierarchical one. */
    private static ScenarioOp insert(RandomGenerator random, Known known) {
        String taxonomy = pick(random, TAXONOMIES);
        String name = unusedName(random, known);
        TermFields.Builder fields = TermFields.builder().name(name);
        boolean givenSlug = false;
        if (random.nextInt(3) == 0) {
            String slug = pick(random, SLUGS);
            fields.slug(slug);
            givenSlug = !slug.isEmpty();
        }
        if (random.nextInt(4) == 0) {
            fields.description(pick(random, DESCRIPTIONS));
        }
        int parent = 0;
        List<Term> mine = known.live(taxonomy);
        if (hierarchical(taxonomy) && !mine.isEmpty() && random.nextBoolean()) {
            Term chosen = pick(random, mine);
            fields.parent(chosen.ref());
            parent = chosen.k;
        }
        known.created(taxonomy, name, givenSlug, parent);
        return new ScenarioOp.InsertTerm(1, taxonomy, fields.build());
    }

    /** An assignment by reference, by known name, to the default term, or with a fresh name (which creates a term). */
    private static ScenarioOp assign(RandomGenerator random, Known known) {
        String taxonomy = pick(random, TAXONOMIES);
        Post post = pick(random, known.posts);
        List<TermItem> items = new ArrayList<>();
        Set<Integer> chosen = new HashSet<>();
        int count = 1 + random.nextInt(3);
        // The terms before the assignment: the driver resolves references before WordPress creates fresh ones.
        List<Term> mine = known.live(taxonomy);
        for (int i = 0; i < count; i++) {
            int kind = random.nextInt(4);
            if (kind == 0 && !mine.isEmpty()) {
                Term term = pick(random, mine);
                items.add(TermItem.term(term.ref()));
                chosen.add(term.k);
            } else if (kind == 1 && !mine.isEmpty()) {
                Term term = pick(random, mine);
                items.add(TermItem.name(term.name));
                chosen.add(term.k);
            } else if (kind == 2 && !taxonomy.equals("post_tag")) {
                items.add(TermItem.term("default"));
                chosen.add(0);
            } else {
                String fresh = pick(random, FRESH);
                items.add(TermItem.name(fresh));
                Term term = known.named(taxonomy, fresh).orElseGet(() -> known.created(taxonomy, fresh, false, 0));
                chosen.add(term.k);
            }
        }
        boolean append = random.nextBoolean();
        Set<Integer> set = post.of(taxonomy);
        if (!append) {
            set.clear();
        }
        set.addAll(chosen);
        return new ScenarioOp.SetPostTerms(1, post.index, taxonomy, items, append);
    }

    /**
     * A change of a live term that changes it: a rename to an unused name, a given slug regenerated from the name,
     * another term's slug (refused as a duplicate), a new description, or (two draws in five) a new parent in a
     * hierarchical taxonomy, half the time none or a term of the term's own subtree (a loop WordPress corrects).
     */
    private static Optional<ScenarioOp> update(RandomGenerator random, Known known) {
        int kind = random.nextInt(5);
        if (kind >= 3) {
            Optional<ScenarioOp> reparented = reparent(random, known);
            if (reparented.isPresent()) {
                return reparented;
            }
        }
        String taxonomy = pick(random, TAXONOMIES);
        List<Term> mine = known.live(taxonomy);
        if (mine.isEmpty()) {
            return Optional.empty();
        }
        Term term = pick(random, mine);
        TermFields.Builder fields = TermFields.builder();
        List<Term> regenerable =
                mine.stream().filter(other -> other.slug.isEmpty()).toList();
        List<Term> slugged = mine.stream()
                .filter(other -> other != term && other.slug.isPresent())
                .toList();
        Term target = term;
        if (kind == 1 && !regenerable.isEmpty()) {
            target = pick(random, regenerable);
            fields.slug("");
            target.slug = Optional.of(slugOf(target.name));
        } else if (kind == 2 && !slugged.isEmpty()) {
            fields.slug(pick(random, slugged).slug.orElseThrow());
        } else if (kind >= 2) {
            fields.description(term.described ? "Changed 'again'" : "Changed 'now'");
            term.described = true;
        } else {
            String name = unusedName(random, known);
            fields.name(name);
            term.name = name;
        }
        return Optional.of(new ScenarioOp.UpdateTerm(1, target.ref(), taxonomy, fields.build()));
    }

    /** A new parent for a term of a hierarchical taxonomy, different from its parent and from itself. */
    private static Optional<ScenarioOp> reparent(RandomGenerator random, Known known) {
        List<Term> movable = new ArrayList<>();
        List<Term> special = new ArrayList<>();
        for (String taxonomy : ScenarioRules.HIERARCHICAL_TAXONOMIES) {
            for (Term term : known.live(taxonomy)) {
                if (!parents(known, term, false).isEmpty()) {
                    movable.add(term);
                }
                if (!parents(known, term, true).isEmpty()) {
                    special.add(term);
                }
            }
        }
        if (movable.isEmpty() && special.isEmpty()) {
            return Optional.empty();
        }
        boolean useSpecial = !special.isEmpty() && (movable.isEmpty() || random.nextBoolean());
        Term term = pick(random, useSpecial ? special : movable);
        String parent = pick(random, parents(known, term, useSpecial));
        int j = parent.equals("none") ? 0 : ScenarioRules.termIndex(parent);
        boolean loop = j != 0 && known.descends(known.terms.get(j - 1), term.k);
        TermFields.Builder fields = TermFields.builder().parent(parent);
        // A third of the moves out of the subtree also take another term's slug: with the parent changing, WordPress
        // gives the slug a suffix instead of refusing it.
        List<Term> slugged = known.live(term.taxonomy).stream()
                .filter(other -> other != term && other.slug.isPresent())
                .toList();
        if (!loop && !slugged.isEmpty() && random.nextInt(3) == 0) {
            fields.slug(pick(random, slugged).slug.orElseThrow());
            term.slug = Optional.empty();
        }
        term.parent = loop ? 0 : j;
        return Optional.of(new ScenarioOp.UpdateTerm(1, term.ref(), term.taxonomy, fields.build()));
    }

    /**
     * The parents a term can move to: another live term of its taxonomy that is not its parent; or ({@code special})
     * none when it has a parent, and the terms of its own subtree.
     */
    private static List<String> parents(Known known, Term term, boolean special) {
        List<String> parents = new ArrayList<>();
        if (special && term.parent != 0) {
            parents.add("none");
        }
        for (Term other : known.live(term.taxonomy)) {
            if (other != term && other.k != term.parent && (!special || known.descends(other, term.k))) {
                parents.add(other.ref());
            }
        }
        return parents;
    }

    /** A published or private post made a draft, preferring one with terms of the scenario. */
    private static Optional<ScenarioOp> unpublish(RandomGenerator random, Known known) {
        Post post = preferred(
                random,
                known.present(),
                candidate -> candidate.status.equals("publish") || candidate.status.equals("private"));
        if (post == null) {
            return Optional.empty();
        }
        post.status = "draft";
        return Optional.of(new ScenarioOp.UpdatePost(
                1, post.index, PostFields.builder().status("draft").build(), false));
    }

    /**
     * A live term deleted: half the time, when there is one, the only category or genre of a post (which then gets the
     * default term); otherwise one with children or posts when the drawn taxonomy has one.
     */
    private static Optional<Term> deleteTerm(RandomGenerator random, Known known) {
        // The taxonomies with a default term where some post has only one term: category and genre alike.
        List<String> soleIn = ScenarioRules.DEFAULT_TERM_TAXONOMIES.stream()
                .filter(taxonomy -> known.live(taxonomy).stream().anyMatch(known::sole))
                .toList();
        Term term;
        if (!soleIn.isEmpty() && random.nextBoolean()) {
            String taxonomy = pick(random, soleIn);
            term = pick(
                    random, known.live(taxonomy).stream().filter(known::sole).toList());
        } else {
            List<String> taxonomies = TAXONOMIES.stream()
                    .filter(taxonomy -> !known.live(taxonomy).isEmpty())
                    .toList();
            if (taxonomies.isEmpty()) {
                return Optional.empty();
            }
            List<Term> mine = known.live(pick(random, taxonomies));
            List<Term> busy = mine.stream()
                    .filter(candidate -> known.hasChildren(candidate) || known.assigned(candidate))
                    .toList();
            term = pick(random, busy.isEmpty() ? mine : busy);
        }
        known.delete(term);
        return Optional.of(term);
    }

    /**
     * An assignment, in the deleted term's taxonomy, of the deleted term's id (absent: skipped) and one term that
     * exists or a fresh name, to a post still there.
     */
    private static ScenarioOp absentAssign(RandomGenerator random, Known known, Term deleted) {
        Post post = pick(random, known.present());
        List<Term> mine = known.live(deleted.taxonomy);
        TermItem other;
        int k;
        if (!mine.isEmpty() && random.nextBoolean()) {
            Term term = pick(random, mine);
            other = TermItem.term(term.ref());
            k = term.k;
        } else {
            String fresh = pick(random, FRESH);
            other = TermItem.name(fresh);
            k = known.named(deleted.taxonomy, fresh)
                    .orElseGet(() -> known.created(deleted.taxonomy, fresh, false, 0))
                    .k;
        }
        TermItem absent = TermItem.term(deleted.ref());
        List<TermItem> items = random.nextBoolean() ? List.of(absent, other) : List.of(other, absent);
        boolean append = random.nextBoolean();
        Set<Integer> set = post.of(deleted.taxonomy);
        if (!append) {
            set.clear();
        }
        set.add(k);
        return new ScenarioOp.SetPostTerms(1, post.index, deleted.taxonomy, items, append);
    }

    /**
     * A removal from a post still there of a category or tag it has (by id or by name), sometimes with a live term it
     * does not have. Genre is left out: a post without a genre would get the default genre on its next save.
     */
    private static Optional<ScenarioOp> removeTerms(RandomGenerator random, Known known) {
        List<Post> posts = new ArrayList<>();
        List<String> taxonomies = new ArrayList<>();
        for (Post post : known.present()) {
            for (String taxonomy : List.of("category", "post_tag")) {
                if (post.of(taxonomy).stream().anyMatch(k -> k != 0)) {
                    posts.add(post);
                    taxonomies.add(taxonomy);
                }
            }
        }
        if (posts.isEmpty()) {
            return Optional.empty();
        }
        int choice = random.nextInt(posts.size());
        Post post = posts.get(choice);
        String taxonomy = taxonomies.get(choice);
        List<Integer> has =
                post.of(taxonomy).stream().filter(k -> k != 0).sorted().toList();
        Term term = known.terms.get(pick(random, has) - 1);
        List<TermItem> items = new ArrayList<>();
        items.add(random.nextBoolean() ? TermItem.term(term.ref()) : TermItem.name(term.name));
        List<Term> others = known.live(taxonomy).stream()
                .filter(other -> !post.of(taxonomy).contains(other.k))
                .toList();
        if (!others.isEmpty() && random.nextInt(3) == 0) {
            items.add(TermItem.term(pick(random, others).ref()));
        }
        post.of(taxonomy).remove(term.k);
        return Optional.of(new ScenarioOp.RemovePostTerms(1, post.index, taxonomy, items));
    }

    /** A get_term of a term made so far (live or deleted), in its taxonomy, in every taxonomy, or in another one. */
    private static ScenarioOp getTerm(RandomGenerator random, Known known) {
        Term term = pick(random, known.terms);
        return switch (random.nextInt(4)) {
            case 0 -> new ScenarioOp.GetTerm(term.ref(), "");
            case 1 -> {
                List<String> others = TAXONOMIES.stream()
                        .filter(taxonomy -> !taxonomy.equals(term.taxonomy))
                        .toList();
                yield new ScenarioOp.GetTerm(term.ref(), pick(random, others));
            }
            default -> new ScenarioOp.GetTerm(term.ref(), term.taxonomy);
        };
    }

    /** A term_exists of a live name (in its case or lower-cased), or of a name from the list; sometimes a parent. */
    private static ScenarioOp exists(RandomGenerator random, Known known) {
        String taxonomy = pick(random, TAXONOMIES);
        List<Term> mine = known.live(taxonomy);
        String value;
        if (!mine.isEmpty() && random.nextBoolean()) {
            String name = pick(random, mine).name;
            value = random.nextBoolean() ? name : ScenarioRules.lowerAscii(name);
        } else {
            value = pick(random, NAMES);
        }
        Optional<String> parent = Optional.empty();
        if (hierarchical(taxonomy) && random.nextInt(3) == 0) {
            parent = Optional.of(
                    mine.isEmpty() || random.nextBoolean()
                            ? "none"
                            : pick(random, mine).ref());
        }
        return new ScenarioOp.TermExists(taxonomy, value, parent);
    }

    private static TermQueryArgs queryArgs(RandomGenerator random, Known known) {
        TermQueryArgs.Builder args = TermQueryArgs.builder();
        // A padded query names a taxonomy with a counted child term when there is one, so padding changes a count.
        boolean pad = random.nextInt(4) == 0;
        List<String> padded = TAXONOMIES.stream().filter(known::countedChild).toList();
        String taxonomy = pick(random, pad && !padded.isEmpty() ? padded : TAXONOMIES);
        boolean named = (pad && !padded.isEmpty()) || random.nextInt(4) != 0;
        if (named) {
            args.taxonomies(List.of(taxonomy));
        }
        String order = pick(random, ORDERS);
        args.orderBy(order);
        args.order(pick(random, DIRECTIONS));
        List<Term> mine = named ? known.live(taxonomy) : List.of();
        if (!mine.isEmpty() && (order.equals("include") || random.nextInt(4) == 0)) {
            List<String> include = new ArrayList<>();
            for (Term term : mine) {
                if (random.nextBoolean()) {
                    include.add(term.ref());
                }
            }
            if (include.isEmpty()) {
                include.add(pick(random, mine).ref());
            }
            args.include(include);
        }
        if (random.nextBoolean()) {
            args.hideEmpty(random.nextBoolean());
        }
        if (random.nextInt(4) == 0) {
            args.hierarchical(false);
        }
        if (random.nextInt(4) == 0) {
            args.number(1 + random.nextInt(3));
            if (random.nextBoolean()) {
                args.offset(random.nextInt(3));
            }
        }
        List<Term> parents = mine.stream().filter(known::hasChildren).toList();
        if (!mine.isEmpty() && random.nextInt(5) == 0) {
            args.exclude(List.of(pick(random, mine).ref()));
        }
        if (!mine.isEmpty() && random.nextInt(5) == 0) {
            args.excludeTree(
                    List.of(pick(random, parents.isEmpty() ? mine : parents).ref()));
        }
        if (hierarchical(taxonomy) && !parents.isEmpty() && random.nextInt(3) == 0) {
            if (random.nextBoolean()) {
                args.parent(pick(random, parents).ref());
            } else {
                args.childOf(pick(random, parents).ref());
            }
        } else if (named && random.nextInt(5) == 0) {
            args.parent("none");
        }
        if (random.nextInt(5) == 0) {
            args.search(pick(random, List.of("a", "ET", "fresh", "s-", "'")));
        }
        if (random.nextInt(6) == 0) {
            args.nameLike(pick(random, List.of("ta", "O'", "\"Hi")));
        }
        if (random.nextInt(6) == 0) {
            args.descriptionLike(pick(random, List.of("about", "'bye'", "kept")));
        }
        if (pad) {
            args.padCounts(true);
        }
        if (random.nextInt(6) == 0) {
            args.childless(true);
        }
        if (random.nextInt(6) == 0) {
            args.posts(List.of(preferred(random, known.present(), post -> true).index));
        }
        if (random.nextInt(8) == 0) {
            args.all(true);
        }
        return args.build();
    }

    /**
     * A post drawn among those {@code allowed}, preferring those with a term of the scenario; null when none is
     * allowed.
     */
    private static Post preferred(RandomGenerator random, List<Post> posts, Predicate<Post> allowed) {
        List<Post> candidates = posts.stream().filter(allowed).toList();
        if (candidates.isEmpty()) {
            return null;
        }
        List<Post> withTerms = candidates.stream()
                .filter(post -> post.terms.stream().anyMatch(set -> set.stream().anyMatch(k -> k != 0)))
                .toList();
        return pick(random, withTerms.isEmpty() ? candidates : withTerms);
    }

    /** The slug WordPress gives a name of the lists: lower-cased letters and digits, quotes dropped, spaces as dashes. */
    static String slugOf(String name) {
        StringBuilder slug = new StringBuilder();
        for (char c : ScenarioRules.lowerAscii(name).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                slug.append(c);
            } else if (c == ' ') {
                slug.append('-');
            }
        }
        return slug.toString();
    }

    private static boolean hierarchical(String taxonomy) {
        return ScenarioRules.HIERARCHICAL_TAXONOMIES.contains(taxonomy);
    }

    private static String unusedName(RandomGenerator random, Known known) {
        List<String> free = new ArrayList<>();
        for (String name : NAMES) {
            if (!known.used.contains(ScenarioRules.lowerAscii(name))) {
                free.add(name);
            }
        }
        String name = pick(random, free);
        known.used.add(ScenarioRules.lowerAscii(name));
        return name;
    }

    private static <T> T pick(RandomGenerator random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
