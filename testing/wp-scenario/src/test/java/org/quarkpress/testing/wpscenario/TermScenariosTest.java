package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TermScenariosTest {

    @Test
    void handWrittenScenariosNameTheirRuleAndUseTerms() {
        List<Scenario> scenarios = TermScenarios.handWritten();
        assertThat(scenarios).hasSize(10);
        for (Scenario scenario : scenarios) {
            assertThat(scenario.rule()).startsWith("TAX");
            assertThat(scenario.variant()).isEqualTo(NetworkVariant.SUBDIRECTORY);
            assertThat(scenario.generated()).isFalse();
            assertThat(scenario.ops()).anyMatch(ScenarioOp.TermOp.class::isInstance);
        }
    }

    @Test
    void scenarioIdsAreDistinctAndWriteBack() {
        Set<String> ids = new HashSet<>();
        for (Scenario scenario : TermScenarios.all()) {
            assertThat(ids.add(scenario.caseId())).as(scenario.caseId()).isTrue();
            assertThat(ScenarioJson.parse(ScenarioJson.toJson(scenario), scenario.generated()))
                    .isEqualTo(scenario);
        }
    }

    @Test
    void generatedScenariosAreStableAndKeepToTheirRules() {
        assertThat(TermScenarios.generated("seed-a", 5)).isEqualTo(TermScenarios.generated("seed-a", 5));
        for (Scenario scenario : TermScenarios.generated("seed-b", 20)) {
            Set<String> names = new HashSet<>();
            List<String> taxonomyOf = new ArrayList<>();
            long inserts = 0;
            for (ScenarioOp op : scenario.ops()) {
                if (op instanceof ScenarioOp.InsertTerm insert) {
                    inserts++;
                    assertThat(names.add(ScenarioRules.lowerAscii(
                                    insert.fields().name().orElseThrow())))
                            .as(scenario.id() + " repeats a name")
                            .isTrue();
                    insert.fields()
                            .parent()
                            .ifPresent(parent -> assertThat(taxonomyOf.get(ScenarioRules.termIndex(parent) - 1))
                                    .as(scenario.id() + " parent " + parent)
                                    .isEqualTo(insert.taxonomy()));
                    taxonomyOf.add(insert.taxonomy());
                }
                if (op instanceof ScenarioOp.InsertPost insert) {
                    assertThat(insert.as()).as(scenario.id()).isEqualTo(1);
                }
                if (op instanceof ScenarioOp.UpdateTerm update) {
                    update.fields()
                            .name()
                            .ifPresent(name -> assertThat(names.add(ScenarioRules.lowerAscii(name)))
                                    .isTrue());
                }
            }
            assertThat(inserts).as(scenario.id()).isBetween(4L, 8L);
            assertThat(scenario.ops())
                    .filteredOn(ScenarioOp.QueryTerms.class::isInstance)
                    .hasSizeGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void allHoldsTheHandWrittenThenTwoSeeds() {
        assertThat(TermScenarios.all()).hasSize(10 + 2 * TermScenarios.GENERATED);
    }

    /**
     * Every generated operation of the two committed seeds reaches the state it is drawn to test, checked by a model of
     * terms and posts kept here apart from the generator's; and over the forty scenarios each state worth comparing is
     * reached at least once.
     */
    @Test
    void generatedOperationsReachTheStatesTheyTest() {
        List<Scenario> generated = new ArrayList<>(TermScenarios.all());
        generated.removeIf(scenario -> !scenario.generated());
        assertThat(generated).hasSize(2 * TermScenarios.GENERATED);
        Set<String> reached = new HashSet<>();
        for (Scenario scenario : generated) {
            new Model(scenario.id(), reached).play(scenario.ops());
        }
        assertThat(reached)
                .containsAll(List.of(
                        "insert repeats a given slug",
                        "insert with a quote",
                        "insert with a parent",
                        "assign by reference",
                        "assign by name of a term",
                        "assign by fresh name",
                        "assign by fresh name again",
                        "assign by name with a quote",
                        "assign the default term",
                        "assign appending",
                        "rename",
                        "slug regenerated",
                        "slug of another term",
                        "re-parent",
                        "re-parent under a descendant",
                        "parent removed",
                        "description changed",
                        "unpublish a post with a counted term",
                        "trash a post with a counted term",
                        "delete a post with a counted term",
                        "delete a term with children",
                        "delete a post's only category",
                        "delete a post's only genre",
                        "delete a flat term with posts",
                        "term_exists finds a name",
                        "term_exists in another case",
                        "term_exists under a parent",
                        "query child_of a term with children",
                        "query parent a term with children",
                        "query hides empty among empty and counted",
                        "query pads counts of a counted child",
                        "query name_like",
                        "query description_like",
                        "query include",
                        "query of every taxonomy",
                        "query exclude",
                        "query exclude_tree of a term with children",
                        "publish a draft by update",
                        "publish_post of a post with a counted term",
                        "untrash a post",
                        "remove a term a post has",
                        "remove by name",
                        "remove leaving a post without a category",
                        "get_term of a live term",
                        "get_term of a deleted term",
                        "get_term in every taxonomy",
                        "get_term in another taxonomy",
                        "slug and parent changed together",
                        "assign an absent id",
                        "assign by numeric name",
                        "insert repeats a given slug in another taxonomy"));
    }

    /** The slugs WordPress gives the generator's names, written out here apart from the generator's helper. */
    private static final Map<String, String> SLUGS = Map.ofEntries(
            Map.entry("Alpha", "alpha"),
            Map.entry("beta", "beta"),
            Map.entry("Gamma", "gamma"),
            Map.entry("delta", "delta"),
            Map.entry("Epsilon", "epsilon"),
            Map.entry("zeta", "zeta"),
            Map.entry("Eta", "eta"),
            Map.entry("theta", "theta"),
            Map.entry("Iota", "iota"),
            Map.entry("kappa", "kappa"),
            Map.entry("Lambda", "lambda"),
            Map.entry("omega", "omega"),
            Map.entry("News", "news"),
            Map.entry("world", "world"),
            Map.entry("Sport", "sport"),
            Map.entry("travel", "travel"),
            Map.entry("Music", "music"),
            Map.entry("books", "books"),
            Map.entry("O'Hara", "ohara"),
            Map.entry("Say \"Hi\"", "say-hi"),
            Map.entry("Fresh A", "fresh-a"),
            Map.entry("Fresh B", "fresh-b"),
            Map.entry("Fresh O'K", "fresh-ok"),
            Map.entry("Fresh D", "fresh-d"),
            Map.entry("1005", "1005"),
            Map.entry("2077", "2077"));

    private static String slugOf(String name) {
        String slug = SLUGS.get(name);
        assertThat(slug).as("a slug for " + name).isNotNull();
        return slug;
    }

    /** The terms and posts a generated scenario makes, as WordPress keeps them. */
    private static final class Model {
        private final String id;
        private final Set<String> reached;
        private final List<String> taxonomy = new ArrayList<>();
        private final List<String> name = new ArrayList<>();
        private final List<Integer> parent = new ArrayList<>();
        /** Each term's slug, null when WordPress gave a given slug a suffix the model does not follow. */
        private final List<String> slug = new ArrayList<>();

        private final List<Boolean> given = new ArrayList<>();
        private final List<String> description = new ArrayList<>();
        private final List<Boolean> live = new ArrayList<>();
        private final List<String> status = new ArrayList<>();
        private final List<Map<String, Set<Integer>>> terms = new ArrayList<>();
        private final Set<String> givenSlugs = new HashSet<>();
        private int op;

        Model(String id, Set<String> reached) {
            this.id = id;
            this.reached = reached;
        }

        void play(List<ScenarioOp> ops) {
            for (op = 0; op < ops.size(); op++) {
                step(ops.get(op));
            }
            // The scenario ends with every remaining post's terms in every taxonomy, in post order.
            List<ScenarioOp> reads = new ArrayList<>();
            for (int post = 1; post <= status.size(); post++) {
                if (!status.get(post - 1).equals("deleted")) {
                    for (String tax : List.of("category", "post_tag", "genre")) {
                        reads.add(new ScenarioOp.PostTerms(post, tax));
                    }
                }
            }
            assertThat(ops.subList(ops.size() - reads.size(), ops.size()))
                    .as(id + " ends with the terms of every remaining post")
                    .isEqualTo(reads);
        }

        private String where() {
            return id + " op" + op;
        }

        private void step(ScenarioOp step) {
            switch (step) {
                case ScenarioOp.InsertPost insert -> {
                    assertThat(insert.as()).as(where()).isEqualTo(1);
                    status.add(insert.fields().status().orElseThrow());
                    Map<String, Set<Integer>> of = new HashMap<>();
                    of.put("category", new HashSet<>(Set.of(0)));
                    of.put("post_tag", new HashSet<>());
                    of.put("genre", new HashSet<>(Set.of(0)));
                    terms.add(of);
                }
                case ScenarioOp.InsertTerm insert -> insert(insert);
                case ScenarioOp.SetPostTerms set -> assign(set);
                case ScenarioOp.UpdateTerm update -> update(update);
                case ScenarioOp.UpdatePost update -> {
                    String before = present(update.post());
                    String after = update.fields().status().orElseThrow();
                    if (after.equals("publish")) {
                        assertThat(before).as(where() + " publishes a draft").isEqualTo("draft");
                        assertThat(update.fields().date())
                                .as(where() + " dates it")
                                .isPresent();
                        assertThat(update.editDate())
                                .as(where() + " keeps the date")
                                .isTrue();
                        reached.add("publish a draft by update");
                    } else {
                        assertThat(before).as(where() + " unpublishes").isIn("publish", "private");
                        assertThat(after).as(where()).isEqualTo("draft");
                        if (before.equals("publish") && counted(update.post())) {
                            reached.add("unpublish a post with a counted term");
                        }
                    }
                    status.set(update.post() - 1, after);
                }
                case ScenarioOp.PublishPost publish -> {
                    assertThat(present(publish.post()))
                            .as(where() + " publishes")
                            .isIn("draft", "private");
                    if (counted(publish.post())) {
                        reached.add("publish_post of a post with a counted term");
                    }
                    status.set(publish.post() - 1, "publish");
                }
                case ScenarioOp.UntrashPost untrash -> {
                    assertThat(present(untrash.post()))
                            .as(where() + " untrashes")
                            .isEqualTo("trash");
                    reached.add("untrash a post");
                    status.set(untrash.post() - 1, "draft");
                }
                case ScenarioOp.RemovePostTerms remove -> remove(remove);
                case ScenarioOp.GetTerm get -> {
                    int k = ScenarioRules.termIndex(get.term());
                    assertThat(k).as(where() + " gets a term made so far").isBetween(1, name.size());
                    reached.add(live.get(k - 1) ? "get_term of a live term" : "get_term of a deleted term");
                    if (get.taxonomy().isEmpty()) {
                        reached.add("get_term in every taxonomy");
                    } else if (!get.taxonomy().equals(taxonomy.get(k - 1))) {
                        reached.add("get_term in another taxonomy");
                    }
                }
                case ScenarioOp.TrashPost trash -> {
                    assertThat(present(trash.post())).as(where() + " trashes").isNotEqualTo("trash");
                    if (status.get(trash.post() - 1).equals("publish") && counted(trash.post())) {
                        reached.add("trash a post with a counted term");
                    }
                    status.set(trash.post() - 1, "trash");
                }
                case ScenarioOp.DeletePost delete -> {
                    present(delete.post());
                    assertThat(delete.force()).as(where() + " deletes for good").isTrue();
                    if (status.get(delete.post() - 1).equals("publish") && counted(delete.post())) {
                        reached.add("delete a post with a counted term");
                    }
                    status.set(delete.post() - 1, "deleted");
                }
                case ScenarioOp.DeleteTerm delete -> deleteTerm(delete);
                case ScenarioOp.PostTerms read -> present(read.post());
                case ScenarioOp.TermExists exists -> {
                    exists.parent().filter(ref -> !ref.equals("none")).ifPresent(ref -> live(ref, exists.taxonomy()));
                    if (exists.parent().isPresent()) {
                        reached.add("term_exists under a parent");
                    }
                    for (int k = 1; k <= name.size(); k++) {
                        if (live.get(k - 1) && taxonomy.get(k - 1).equals(exists.taxonomy())) {
                            if (name.get(k - 1).equals(exists.value())) {
                                reached.add("term_exists finds a name");
                            } else if (ScenarioRules.lowerAscii(name.get(k - 1))
                                    .equals(ScenarioRules.lowerAscii(exists.value()))) {
                                reached.add("term_exists in another case");
                            }
                        }
                    }
                }
                case ScenarioOp.QueryTerms query -> query(query.args());
                default -> {}
            }
        }

        private void insert(ScenarioOp.InsertTerm insert) {
            int parentK = 0;
            if (insert.fields().parent().isPresent()) {
                parentK = live(insert.fields().parent().get(), insert.taxonomy());
                reached.add("insert with a parent");
            }
            String given = insert.fields().slug().orElse("");
            String stored = given.isEmpty() ? null : given;
            if (!given.isEmpty() && !givenSlugs.add(insert.taxonomy() + " " + given)) {
                reached.add("insert repeats a given slug");
                // A suffix: the parent's slug under a parent, else the first free number over every taxonomy.
                stored = null;
                if (parentK == 0) {
                    int n = 2;
                    while (slug.contains(given + "-" + n)) {
                        n++;
                    }
                    stored = given + "-" + n;
                }
            } else if (!given.isEmpty()
                    && givenSlugs.stream()
                            .anyMatch(other ->
                                    other.endsWith(" " + given) && !other.equals(insert.taxonomy() + " " + given))) {
                reached.add("insert repeats a given slug in another taxonomy");
            }
            String termName = insert.fields().name().orElseThrow();
            if (termName.indexOf('\'') >= 0 || termName.indexOf('"') >= 0) {
                reached.add("insert with a quote");
            }
            create(insert.taxonomy(), termName, stored, !given.isEmpty(), parentK);
            description.set(
                    description.size() - 1, insert.fields().description().orElse(""));
        }

        private void create(String tax, String termName, String storedSlug, boolean givenSlug, int parentK) {
            taxonomy.add(tax);
            name.add(termName);
            parent.add(parentK);
            slug.add(givenSlug ? storedSlug : slugOf(termName));
            given.add(givenSlug);
            description.add("");
            live.add(true);
        }

        private void assign(ScenarioOp.SetPostTerms set) {
            present(set.post());
            int before = name.size();
            Set<Integer> chosen = new HashSet<>();
            for (TermItem item : set.terms()) {
                if (item.term().isPresent()) {
                    String ref = item.term().get();
                    if (ref.equals("default")) {
                        assertThat(set.taxonomy()).as(where()).isNotEqualTo("post_tag");
                        chosen.add(0);
                        reached.add("assign the default term");
                    } else if (absent(ref, set.taxonomy())) {
                        assertThat(set.terms())
                                .as(where() + " assigns an absent id with a term")
                                .hasSize(2);
                        reached.add("assign an absent id");
                    } else {
                        int k = live(ref, set.taxonomy());
                        assertThat(k)
                                .as(where() + " names a term made before the assignment")
                                .isLessThanOrEqualTo(before);
                        chosen.add(k);
                        reached.add("assign by reference");
                    }
                    continue;
                }
                String text = item.name().get();
                if (text.indexOf('\'') >= 0 || text.indexOf('"') >= 0) {
                    reached.add("assign by name with a quote");
                }
                if (Character.isDigit(text.charAt(0))) {
                    reached.add("assign by numeric name");
                }
                int found = named(set.taxonomy(), text);
                boolean fresh = TermScenarios.FRESH.contains(text);
                if (found == 0) {
                    assertThat(fresh)
                            .as(where() + " a name item creates only fresh names: " + text)
                            .isTrue();
                    create(set.taxonomy(), text, null, false, 0);
                    found = name.size();
                    reached.add("assign by fresh name");
                } else {
                    reached.add(fresh ? "assign by fresh name again" : "assign by name of a term");
                }
                chosen.add(found);
            }
            Set<Integer> current = terms.get(set.post() - 1).get(set.taxonomy());
            if (set.append()) {
                reached.add("assign appending");
            } else {
                current.clear();
            }
            current.addAll(chosen);
        }

        private void update(ScenarioOp.UpdateTerm update) {
            int k = live(update.term(), update.taxonomy());
            TermFields fields = update.fields();
            assertThat(fields)
                    .as(where() + " changes one field")
                    .satisfiesAnyOf(
                            f -> assertThat(f.name()).isPresent(),
                            f -> assertThat(f.slug()).isPresent(),
                            f -> assertThat(f.parent()).isPresent(),
                            f -> assertThat(f.description()).isPresent());
            fields.name().ifPresent(newName -> {
                assertThat(newName).as(where() + " renames").isNotEqualTo(name.get(k - 1));
                name.set(k - 1, newName);
                reached.add("rename");
            });
            fields.slug().ifPresent(newSlug -> {
                if (newSlug.isEmpty()) {
                    assertThat(given.get(k - 1) || slug.get(k - 1) == null)
                            .as(where() + " regenerates a given or suffixed slug")
                            .isTrue();
                    assertThat(slugOf(name.get(k - 1)))
                            .as(where() + " regenerates a different slug")
                            .isNotEqualTo(slug.get(k - 1));
                    slug.set(k - 1, slugOf(name.get(k - 1)));
                    given.set(k - 1, false);
                    reached.add("slug regenerated");
                } else {
                    boolean held = false;
                    for (int j = 1; j <= slug.size(); j++) {
                        held |= j != k
                                && live.get(j - 1)
                                && taxonomy.get(j - 1).equals(update.taxonomy())
                                && slug.get(j - 1).equals(newSlug);
                    }
                    assertThat(held)
                            .as(where() + " takes another term's slug " + newSlug)
                            .isTrue();
                    if (fields.parent().isPresent()) {
                        // The parent changes, so WordPress suffixes the slug instead of refusing it.
                        slug.set(k - 1, null);
                        reached.add("slug and parent changed together");
                    } else {
                        reached.add("slug of another term");
                    }
                }
            });
            fields.parent().ifPresent(ref -> {
                int before = parent.get(k - 1);
                int after = ref.equals("none") ? 0 : live(ref, update.taxonomy());
                assertThat(after).as(where() + " changes the parent").isNotEqualTo(before);
                assertThat(after).as(where() + " is not its own parent").isNotEqualTo(k);
                if (fields.slug().isPresent()) {
                    assertThat(after != 0 && descends(after, k))
                            .as(where() + " moves the term when it also takes a slug")
                            .isFalse();
                }
                if (after == 0) {
                    reached.add("parent removed");
                } else if (descends(after, k)) {
                    reached.add("re-parent under a descendant");
                    after = 0;
                } else {
                    reached.add("re-parent");
                }
                parent.set(k - 1, after);
            });
            fields.description().ifPresent(text -> {
                assertThat(text).as(where() + " changes the description").isNotEqualTo(description.get(k - 1));
                description.set(k - 1, text);
                reached.add("description changed");
            });
        }

        private void deleteTerm(ScenarioOp.DeleteTerm delete) {
            int k = live(delete.term(), delete.taxonomy());
            boolean busyExists = false;
            for (int j = 1; j <= name.size(); j++) {
                busyExists |= live.get(j - 1) && taxonomy.get(j - 1).equals(delete.taxonomy()) && busy(j);
            }
            if (busyExists) {
                assertThat(busy(k))
                        .as(where() + " deletes a term with children or posts")
                        .isTrue();
            }
            if (hasChildren(k)) {
                reached.add("delete a term with children");
            }
            for (int post = 1; post <= terms.size(); post++) {
                Set<Integer> of = terms.get(post - 1).get(delete.taxonomy());
                if (!status.get(post - 1).equals("deleted") && of.contains(k)) {
                    if (delete.taxonomy().equals("post_tag")) {
                        reached.add("delete a flat term with posts");
                    } else if (of.size() == 1) {
                        reached.add("delete a post's only " + delete.taxonomy());
                    }
                }
                if (of.remove(k) && of.isEmpty() && !delete.taxonomy().equals("post_tag")) {
                    of.add(0);
                }
            }
            for (int j = 1; j <= parent.size(); j++) {
                if (live.get(j - 1) && parent.get(j - 1) == k) {
                    parent.set(j - 1, parent.get(k - 1));
                }
            }
            live.set(k - 1, false);
        }

        private void query(TermQueryArgs args) {
            String tax = args.defaultTaxonomy();
            if (args.taxonomies().isEmpty()) {
                reached.add("query of every taxonomy");
                assertThat(args.include())
                        .as(where() + " includes only within a named taxonomy")
                        .isEmpty();
            }
            for (String ref : args.include()) {
                live(ref, tax);
                reached.add("query include");
            }
            for (String ref : args.exclude()) {
                live(ref, tax);
                reached.add("query exclude");
            }
            for (String ref : args.excludeTree()) {
                if (hasChildren(live(ref, tax))) {
                    reached.add("query exclude_tree of a term with children");
                }
            }
            if (args.padCounts()) {
                List<String> padded = new ArrayList<>();
                for (String other : List.of("category", "post_tag", "genre")) {
                    if (countedChild(other)) {
                        padded.add(other);
                    }
                }
                if (!padded.isEmpty()) {
                    assertThat(args.taxonomies())
                            .as(where() + " pads a taxonomy with a counted child")
                            .hasSize(1)
                            .first()
                            .isIn(padded);
                }
            }
            args.parent().filter(ref -> !ref.equals("none")).ifPresent(ref -> {
                assertThat(hasChildren(live(ref, tax)))
                        .as(where() + " parent has children")
                        .isTrue();
                reached.add("query parent a term with children");
            });
            args.childOf().ifPresent(ref -> {
                assertThat(hasChildren(live(ref, tax)))
                        .as(where() + " child_of has children")
                        .isTrue();
                reached.add("query child_of a term with children");
            });
            args.posts().forEach(this::present);
            args.nameLike().ifPresent(text -> reached.add("query name_like"));
            args.descriptionLike().ifPresent(text -> reached.add("query description_like"));
            if (args.taxonomies().isEmpty()) {
                return;
            }
            boolean empty = false;
            boolean counted = false;
            boolean countedChild = false;
            for (int k = 1; k <= name.size(); k++) {
                if (live.get(k - 1) && taxonomy.get(k - 1).equals(tax)) {
                    boolean has = count(k) > 0;
                    empty |= !has;
                    counted |= has;
                    countedChild |= has && parent.get(k - 1) != 0;
                }
            }
            if (args.hideEmpty().orElse(true) && empty && counted) {
                reached.add("query hides empty among empty and counted");
            }
            if (args.padCounts() && countedChild) {
                reached.add("query pads counts of a counted child");
            }
        }

        private boolean countedChild(String tax) {
            for (int k = 1; k <= name.size(); k++) {
                if (live.get(k - 1) && taxonomy.get(k - 1).equals(tax) && parent.get(k - 1) != 0 && count(k) > 0) {
                    return true;
                }
            }
            return false;
        }

        /** Whether a reference names a deleted term of the taxonomy (an absent id). */
        private boolean absent(String ref, String tax) {
            int k = ScenarioRules.termIndex(ref);
            return k >= 1
                    && k <= name.size()
                    && !live.get(k - 1)
                    && taxonomy.get(k - 1).equals(tax);
        }

        private void remove(ScenarioOp.RemovePostTerms remove) {
            present(remove.post());
            assertThat(remove.taxonomy()).as(where() + " keeps genres").isNotEqualTo("genre");
            Set<Integer> current = terms.get(remove.post() - 1).get(remove.taxonomy());
            boolean removes = false;
            for (TermItem item : remove.terms()) {
                int k = item.term().isPresent()
                        ? live(item.term().get(), remove.taxonomy())
                        : named(remove.taxonomy(), item.name().get());
                assertThat(k).as(where() + " removes a term that exists").isPositive();
                if (item.name().isPresent()) {
                    reached.add("remove by name");
                }
                removes |= current.remove(k);
            }
            assertThat(removes).as(where() + " removes a term the post has").isTrue();
            reached.add("remove a term a post has");
            if (remove.taxonomy().equals("category") && current.isEmpty()) {
                reached.add("remove leaving a post without a category");
            }
        }

        /** The k of a reference that must name a live term of the taxonomy. */
        private int live(String ref, String tax) {
            int k = ScenarioRules.termIndex(ref);
            assertThat(k).as(where() + " names " + ref).isBetween(1, name.size());
            assertThat(live.get(k - 1)).as(where() + " names a live " + ref).isTrue();
            assertThat(taxonomy.get(k - 1))
                    .as(where() + " " + ref + "'s taxonomy")
                    .isEqualTo(tax);
            return k;
        }

        private String present(int post) {
            assertThat(post).as(where() + " names post " + post).isBetween(1, status.size());
            assertThat(status.get(post - 1))
                    .as(where() + " names a post still there")
                    .isNotEqualTo("deleted");
            return status.get(post - 1);
        }

        private int named(String tax, String text) {
            for (int k = 1; k <= name.size(); k++) {
                if (live.get(k - 1)
                        && taxonomy.get(k - 1).equals(tax)
                        && ScenarioRules.lowerAscii(name.get(k - 1)).equals(ScenarioRules.lowerAscii(text))) {
                    return k;
                }
            }
            return 0;
        }

        private boolean hasChildren(int k) {
            for (int j = 1; j <= parent.size(); j++) {
                if (live.get(j - 1) && parent.get(j - 1) == k) {
                    return true;
                }
            }
            return false;
        }

        private boolean descends(int j, int ancestor) {
            for (int up = parent.get(j - 1); up != 0; up = parent.get(up - 1)) {
                if (up == ancestor) {
                    return true;
                }
            }
            return false;
        }

        private boolean busy(int k) {
            if (hasChildren(k)) {
                return true;
            }
            for (int post = 1; post <= terms.size(); post++) {
                if (!status.get(post - 1).equals("deleted")
                        && terms.get(post - 1).get(taxonomy.get(k - 1)).contains(k)) {
                    return true;
                }
            }
            return false;
        }

        private boolean counted(int post) {
            for (Set<Integer> of : terms.get(post - 1).values()) {
                if (of.stream().anyMatch(k -> k != 0)) {
                    return true;
                }
            }
            return false;
        }

        private int count(int k) {
            int count = 0;
            for (int post = 1; post <= terms.size(); post++) {
                if (status.get(post - 1).equals("publish")
                        && terms.get(post - 1).get(taxonomy.get(k - 1)).contains(k)) {
                    count++;
                }
            }
            return count;
        }
    }
}
