package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class TermScenarioFormatTest {

    static final String EXAMPLE = """
            {"id": "term-example", "rule": "TAX16", "variant": "subdirectory", "ops": [
              {"op": "create_user", "username": "ed", "given_name": "", "family_name": ""},
              {"op": "add_user", "user": 1, "site": 0, "role": "editor"},
              {"op": "insert_post", "as": 1, "fields": {"title": "Hello", "status": "publish"}},
              {"op": "insert_term", "as": 1, "taxonomy": "category", "fields": {"name": "World"}},
              {"op": "insert_term", "as": 1, "taxonomy": "category",
                "fields": {"name": "News", "slug": "", "description": "<b>d</b>", "parent": "t1"}},
              {"op": "update_term", "as": 1, "term": "t2", "taxonomy": "category", "fields": {"parent": "none"}},
              {"op": "set_post_terms", "as": 1, "post": 1, "taxonomy": "post_tag",
                "terms": [{"term": "t1"}, {"name": "Fresh"}], "append": true},
              {"op": "remove_post_terms", "as": 1, "post": 1, "taxonomy": "post_tag", "terms": [{"name": "Fresh"}]},
              {"op": "post_terms", "post": 1, "taxonomy": "category"},
              {"op": "query_terms", "as": 0, "args": {"taxonomies": ["category"], "orderby": "count",
                "order": "desc", "hide_empty": false, "include": ["t1", "default"], "posts": [1], "number": 2}},
              {"op": "get_term", "term": "default", "taxonomy": ""},
              {"op": "term_exists", "taxonomy": "category", "value": "News", "parent": "t1"},
              {"op": "delete_term", "as": 1, "term": "t3", "taxonomy": "post_tag"},
              {"op": "query_terms", "as": 1, "args": {"taxonomies": ["genre", "category"], "orderby": "none",
                "hierarchical": true, "pad_counts": true, "get_all": true, "exclude": ["t1"], "exclude_tree": ["t2"],
                "slugs": ["news"], "names": ["World"], "search": "or", "name_like": "Wo", "description_like": "d",
                "parent": "none", "child_of": "default", "childless": true, "offset": 1}}
            ]}
            """;

    private static String withOps(String ops) {
        return "{\"id\": \"bad\", \"rule\": \"TAX1\", \"variant\": \"subdirectory\", \"ops\": [" + ops + "]}";
    }

    @Test
    void everyTermOperationIsReadAndWrittenBack() {
        Scenario scenario = ScenarioJson.parse(EXAMPLE);
        assertThat(scenario.ops())
                .contains(
                        new ScenarioOp.InsertTerm(
                                1,
                                "category",
                                TermFields.builder().name("World").build()),
                        new ScenarioOp.InsertTerm(
                                1,
                                "category",
                                TermFields.builder()
                                        .name("News")
                                        .slug("")
                                        .description("<b>d</b>")
                                        .parent("t1")
                                        .build()),
                        new ScenarioOp.UpdateTerm(
                                1,
                                "t2",
                                "category",
                                TermFields.builder().parent("none").build()),
                        new ScenarioOp.SetPostTerms(
                                1, 1, "post_tag", List.of(TermItem.term("t1"), TermItem.name("Fresh")), true),
                        new ScenarioOp.RemovePostTerms(1, 1, "post_tag", List.of(TermItem.name("Fresh"))),
                        new ScenarioOp.PostTerms(1, "category"),
                        new ScenarioOp.GetTerm("default", ""),
                        new ScenarioOp.TermExists("category", "News", Optional.of("t1")),
                        new ScenarioOp.DeleteTerm(1, "t3", "post_tag"));
        ScenarioOp.QueryTerms query = (ScenarioOp.QueryTerms) scenario.ops().get(9);
        assertThat(query.args().taxonomies()).containsExactly("category");
        assertThat(query.args().orderBy()).contains("count");
        assertThat(query.args().hideEmpty()).contains(false);
        assertThat(query.args().include()).containsExactly("t1", "default");
        assertThat(query.args().posts()).containsExactly(1);
        assertThat(query.args().number()).isEqualTo(OptionalInt.of(2));
        assertThat(query.args().unordered()).isFalse();
        assertThat(query.args().defaultTaxonomy()).isEqualTo("category");
        ScenarioOp.QueryTerms every = (ScenarioOp.QueryTerms) scenario.ops().get(13);
        assertThat(every.args())
                .isEqualTo(TermQueryArgs.builder()
                        .taxonomies(List.of("genre", "category"))
                        .orderBy("none")
                        .hierarchical(true)
                        .padCounts(true)
                        .all(true)
                        .exclude(List.of("t1"))
                        .excludeTree(List.of("t2"))
                        .slugs(List.of("news"))
                        .names(List.of("World"))
                        .search("or")
                        .nameLike("Wo")
                        .descriptionLike("d")
                        .parent("none")
                        .childOf("default")
                        .childless(true)
                        .offset(1)
                        .build());
        assertThat(every.args().unordered()).isTrue();
        assertThat(every.args().defaultTaxonomy()).isEqualTo("genre");
        assertThat(ScenarioJson.parse(ScenarioJson.write(scenario))).isEqualTo(scenario);
    }

    @Test
    void termReferencesMustNameTermsThatCanExist() {
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"get_term\", \"term\": \"t1\", \"taxonomy\": \"category\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 1 refers to term t1, but at most 0 terms were created before it");
        assertThatThrownBy(() ->
                        ScenarioJson.parse(withOps("{\"op\": \"insert_term\", \"as\": 0, \"taxonomy\": \"post_tag\","
                                + " \"fields\": {\"name\": \"x\", \"parent\": \"none\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario gives a parent only in a hierarchical taxonomy (category, genre): post_tag");
        assertThatThrownBy(() ->
                        ScenarioJson.parse(withOps("{\"op\": \"insert_term\", \"as\": 0, \"taxonomy\": \"nav_menu\","
                                + " \"fields\": {\"name\": \"x\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario's taxonomy is one of category, post_tag, genre: nav_menu");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"insert_term\", \"as\": 0, \"taxonomy\": \"post_tag\", \"fields\": {}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("insert_term gives a name");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"delete_term\", \"as\": 0, \"term\": \"x1\", \"taxonomy\": \"post_tag\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario term reference is t<k> (k from 1) or default: x1");
    }

    @Test
    void termTextIsPrintableAsciiWithoutBackslashesAndNumbersAreLarge() {
        assertThatThrownBy(() -> TermFields.builder()
                        .name("Caf" + Character.toString(0xE9))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario's term text is printable ASCII, tab or line feed, without backslashes");
        assertThatThrownBy(() -> TermFields.builder().name("a\\b").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario's term text is printable ASCII, tab or line feed, without backslashes");
        assertThatThrownBy(() -> TermFields.builder().slug("12").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario term name or slug that is a number is 1000 or more: 12");
        assertThat(TermFields.builder().name("1001").slug("2002").build().name())
                .contains("1001");
    }

    @Test
    void namesAndSlugsWhoseSlugFormStartsWithASmallNumberAreRefused() {
        for (String name : List.of("3 Days", "12!", "!3-x", "<b>7</b> up", "&amp;9 lives", "%2F5 five", "999x")) {
            assertThatThrownBy(() -> TermFields.builder().name(name).build())
                    .as(name)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("a scenario term name or slug that ");
        }
        assertThatThrownBy(() -> TermFields.builder().slug("3-days").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario term name or slug that starts with a number starts with 1000 or more: 3-days");
        assertThatThrownBy(() -> TermItem.name("5 a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario term name or slug that starts with a number starts with 1000 or more: 5 a");
        assertThatThrownBy(() -> new ScenarioOp.TermExists("category", "4 x", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario term name or slug that starts with a number starts with 1000 or more: 4 x");
        assertThatThrownBy(() -> TermQueryArgs.builder().slugs(List.of("6-a")).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TermQueryArgs.builder().names(List.of("6 a")).build())
                .isInstanceOf(IllegalArgumentException.class);
        for (String name : List.of("Days 3", "1000 days", "007", "0 days", "a1", "")) {
            assertThat(TermFields.builder().name(name).build().name()).contains(name);
        }
        assertThat(TermFields.builder().description("3 apples").build().description())
                .contains("3 apples");
    }

    @Test
    void defaultIsNamedOnlyWhereItsTaxonomyHasADefaultTerm() {
        String message = "a scenario names default only in a taxonomy with a default term (category, genre): post_tag";
        assertThatThrownBy(() -> new ScenarioOp.DeleteTerm(0, "default", "post_tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> new ScenarioOp.UpdateTerm(
                        0, "default", "post_tag", TermFields.builder().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> new ScenarioOp.SetPostTerms(1, 1, "post_tag", List.of(TermItem.term("default")), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> new ScenarioOp.RemovePostTerms(1, 1, "post_tag", List.of(TermItem.term("default"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> new ScenarioOp.GetTerm("default", "post_tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> new ScenarioOp.TermExists("post_tag", "x", Optional.of("default")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        for (TermQueryArgs.Builder args : List.of(
                TermQueryArgs.builder().include(List.of("default")),
                TermQueryArgs.builder().exclude(List.of("default")),
                TermQueryArgs.builder().excludeTree(List.of("default")),
                TermQueryArgs.builder().parent("default"),
                TermQueryArgs.builder().childOf("default"))) {
            assertThatThrownBy(() ->
                            args.taxonomies(List.of("post_tag", "category")).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(message);
            assertThat(args.taxonomies(List.of("genre", "post_tag")).build().defaultTaxonomy())
                    .isEqualTo("genre");
        }
        assertThat(TermQueryArgs.builder().include(List.of("default")).build().defaultTaxonomy())
                .isEqualTo("category");
        assertThat(new ScenarioOp.GetTerm("default", "").taxonomy()).isEmpty();
    }

    @Test
    void genresDefaultTermKeepsItsNameOrItsSlug() {
        assertThatThrownBy(() -> new ScenarioOp.UpdateTerm(
                        1,
                        "default",
                        "genre",
                        TermFields.builder().name("Rock").slug("rock").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("a scenario does not change both the name and the slug of genre's default");
        assertThat(new ScenarioOp.UpdateTerm(
                                1,
                                "default",
                                "genre",
                                TermFields.builder().name("Rock").build())
                        .fields()
                        .name())
                .contains("Rock");
        assertThat(new ScenarioOp.UpdateTerm(
                                1,
                                "default",
                                "category",
                                TermFields.builder().name("Rock").slug("rock").build())
                        .term())
                .isEqualTo("default");
        assertThat(new ScenarioOp.UpdateTerm(
                                1,
                                "t1",
                                "genre",
                                TermFields.builder().name("R").slug("r").build())
                        .term())
                .isEqualTo("t1");
    }

    @Test
    void namesInAssignmentsMayCreateTermsThatLaterOpsName() {
        Scenario scenario = ScenarioJson.parse(
                withOps("{\"op\": \"create_user\", \"username\": \"ed\", \"given_name\": \"\", \"family_name\": \"\"},"
                        + "{\"op\": \"insert_post\", \"as\": 1, \"fields\": {\"title\": \"P\"}},"
                        + "{\"op\": \"set_post_terms\", \"as\": 1, \"post\": 1, \"taxonomy\": \"post_tag\","
                        + " \"terms\": [{\"name\": \"a\"}, {\"name\": \"b\"}], \"append\": false},"
                        + "{\"op\": \"get_term\", \"term\": \"t2\", \"taxonomy\": \"post_tag\"}"));
        assertThat(scenario.ops()).hasSize(4);
    }
}
