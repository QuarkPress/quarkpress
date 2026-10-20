package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class PostScenarioFormatTest {

    static final String EXAMPLE = """
            {"id": "post-example", "rule": "POST31", "variant": "subdirectory", "ops": [
              {"op": "create_user", "username": "ed", "given_name": "", "family_name": ""},
              {"op": "add_user", "user": 1, "site": 0, "role": "editor"},
              {"op": "site_option", "name": "timezone_string", "value": "Europe/Paris"},
              {"op": "insert_post", "as": 1, "fields": {"title": "Hello", "status": "publish",
                "date": "2020-05-05 10:00:00", "menu_order": 2}},
              {"op": "insert_post", "as": 0, "fields": {"type": "page", "title": "Child", "parent": 1}},
              {"op": "update_post", "as": 1, "post": 1, "fields": {"slug": ""}, "edit_date": true},
              {"op": "publish_post", "post": 2},
              {"op": "stick", "post": 1, "sticky": true},
              {"op": "front_page", "option": "page_on_front", "post": 2},
              {"op": "trash_post", "as": 0, "post": 1},
              {"op": "untrash_post", "as": 0, "post": 1},
              {"op": "query_posts", "as": 0, "args": {"types": ["post", "page"], "author_in": [1],
                "orderby": ["title asc"], "per_page": 5, "sticky_first": true}},
              {"op": "page_uri", "post": 2},
              {"op": "read_post", "post": 1},
              {"op": "delete_post", "as": 1, "post": 2, "force": true}
            ]}
            """;

    private static String withOps(String ops) {
        return "{\"id\": \"bad\", \"rule\": \"POST1\", \"variant\": \"subdirectory\", \"ops\": [" + ops + "]}";
    }

    @Test
    void everyPostOperationIsReadAndWrittenBack() {
        Scenario scenario = ScenarioJson.parse(EXAMPLE);
        assertThat(scenario.ops())
                .contains(
                        new ScenarioOp.SiteOption("timezone_string", "Europe/Paris"),
                        new ScenarioOp.InsertPost(
                                1,
                                PostFields.builder()
                                        .title("Hello")
                                        .status("publish")
                                        .date("2020-05-05 10:00:00")
                                        .menuOrder(2)
                                        .build()),
                        new ScenarioOp.UpdatePost(
                                1, 1, PostFields.builder().slug("").build(), true),
                        new ScenarioOp.PublishPost(2),
                        new ScenarioOp.Stick(1, true),
                        new ScenarioOp.FrontPage("page_on_front", 2),
                        new ScenarioOp.TrashPost(0, 1),
                        new ScenarioOp.UntrashPost(0, 1),
                        new ScenarioOp.PageUri(2),
                        new ScenarioOp.ReadPost(1),
                        new ScenarioOp.DeletePost(1, 2, true));
        ScenarioOp.QueryPosts query = (ScenarioOp.QueryPosts) scenario.ops().get(11);
        assertThat(query.args().types()).containsExactly("post", "page");
        assertThat(query.args().authorIn()).containsExactly(1);
        assertThat(query.args().orderBy()).containsExactly("title asc");
        assertThat(query.args().perPage()).isEqualTo(OptionalInt.of(5));
        assertThat(query.args().stickyFirst()).isTrue();
        assertThat(query.args().search()).isEqualTo(Optional.empty());
        assertThat(ScenarioJson.parse(ScenarioJson.write(scenario))).isEqualTo(scenario);
    }

    @Test
    void referencesMustNameInsertedPostsAndCreatedUsers() {
        assertThatThrownBy(() -> ScenarioJson.parse(withOps("{\"op\": \"read_post\", \"post\": 1}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 1 refers to post #1, but only 0 posts were inserted before it");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"insert_post\", \"as\": 2, \"fields\": {\"title\": \"x\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 1 acts as user #2, but only 0 users were created before it");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"insert_post\", \"as\": 0, \"fields\": {\"parent\": 1}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 1 refers to post #1, but only 0 posts were inserted before it");
    }

    @Test
    void unknownKeysAndBadValuesAreRefused() {
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"insert_post\", \"as\": 0, \"fields\": {\"titel\": \"x\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown key 'titel'");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps(
                        "{\"op\": \"insert_post\", \"as\": 0, \"fields\": {\"date\": \"2026-01-15 12:00:00\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario post date is Y-m-d H:i:s before 2025 or from 2090 on, away from the run:"
                        + " 2026-01-15 12:00:00");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps(
                        "{\"op\": \"insert_post\", \"as\": 0, \"fields\": {\"date\": \"2020-02-30 10:00:00\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2020-02-30 10:00:00");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"site_option\", \"name\": \"home\"," + " \"value\": \"x\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("home");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"query_posts\", \"as\": 0, \"args\":" + " {\"orderby\": [\"rand asc\"]}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rand asc");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"insert_post\", \"as\": 0, \"fields\": {\"title\": \"a\\u0000b\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    void authorReferencesAreNamedAsReferences() {
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"insert_post\", \"as\": 0, \"fields\": {\"author\": 2}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 1 refers to user #2, but only 0 users were created before it");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"query_posts\", \"as\": 0, \"args\": {\"author_not_in\": [1]}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 1 refers to user #1, but only 0 users were created before it");
    }

    @Test
    void postOperationsRunOnlyOnTheSubdirectoryNetwork() {
        assertThatThrownBy(() -> ScenarioJson.parse("{\"id\": \"bad\", \"rule\": \"POST1\", \"variant\": \"subdomain\","
                        + " \"ops\": [{\"op\": \"insert_post\", \"as\": 0, \"fields\": {\"title\": \"x\"}}]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 1 is a post operation, which runs on the subdirectory network only");
    }

    @Test
    void negativeReferencesAreRefused() {
        for (String op : List.of(
                "{\"op\": \"insert_post\", \"as\": -1, \"fields\": {}}",
                "{\"op\": \"front_page\", \"option\": \"page_on_front\", \"post\": -1}",
                "{\"op\": \"query_posts\", \"as\": 0, \"args\": {\"parent\": -1}}",
                "{\"op\": \"insert_post\", \"as\": 0, \"fields\": {\"parent\": -1}}")) {
            assertThatThrownBy(() -> ScenarioJson.parse(withOps(op)))
                    .as(op)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("0 (none) or positive");
        }
        for (String op : List.of(
                "{\"op\": \"read_post\", \"post\": 0}",
                "{\"op\": \"trash_post\", \"as\": 0, \"post\": 0}",
                "{\"op\": \"stick\", \"post\": -2, \"sticky\": true}")) {
            assertThatThrownBy(() -> ScenarioJson.parse(withOps(op)))
                    .as(op)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("names a post: 1 or more");
        }
    }

    @Test
    void noneOrdersAlone() {
        assertThatThrownBy(() -> QueryArgs.builder()
                        .orderBy("none asc")
                        .orderBy("date desc")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario order by none is the only order");
        assertThat(QueryArgs.builder().orderBy("none desc").perPage(-1).build().orderBy())
                .containsExactly("none desc");
    }

    @Test
    void postTextAllowsTabsAndLineFeedsButNoSurrogates() {
        String tabbed = "a\tb\nc" + new String(Character.toChars(0xE9));
        assertThat(PostFields.builder().title(tabbed).build().title()).contains(tabbed);
        String emoji = new String(Character.toChars(0x1F600));
        assertThatThrownBy(() -> PostFields.builder().content(emoji).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no surrogates");
        assertThatThrownBy(() -> PostFields.builder().content("x".repeat(2_001)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2,000");
    }

    @Test
    void queriesNameOnlyTypesAndStatusesBothSidesHave() {
        assertThatThrownBy(() -> QueryArgs.builder().types("revision").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario query type is one of post, page, any: revision");
        assertThatThrownBy(() -> QueryArgs.builder().statuses("inherit").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario query status is one of publish, future, draft, pending, private, trash, any:"
                        + " inherit");
        assertThat(QueryArgs.builder()
                        .types("any")
                        .statuses("trash", "any")
                        .build()
                        .statuses())
                .containsExactly("trash", "any");
    }

    @Test
    void anUnorderedQueryIsNotPaged() {
        for (QueryArgs.Builder paged : List.of(
                QueryArgs.builder().orderBy("none asc"),
                QueryArgs.builder().orderBy("none asc").perPage(5),
                QueryArgs.builder().orderBy("none asc").perPage(-1).page(2),
                QueryArgs.builder().orderBy("none asc").perPage(-1).offset(1))) {
            assertThatThrownBy(paged::build)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("a scenario query ordered by none is not paged: per_page -1, no page or offset");
        }
        assertThat(QueryArgs.builder().orderBy("none desc").perPage(-1).build().unordered())
                .isTrue();
        assertThat(QueryArgs.builder().orderBy("date desc").build().unordered()).isFalse();
    }

    @Test
    void aNumericSlugIsBeyondWordPressIds() {
        for (String slug : List.of("7", "999-2", "12__trashed")) {
            assertThatThrownBy(() -> PostFields.builder().slug(slug).build())
                    .as(slug)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                            "a scenario slug that is a number is 1000 or more, beyond WordPress's post ids: " + slug);
        }
        for (String slug : List.of("1000", "1000-2", "7a", "0123", "", "4-things")) {
            assertThat(PostFields.builder().slug(slug).build().slug()).as(slug).contains(slug);
        }
    }

    @Test
    void theExampleBuildsWithTheBuilders() {
        QueryArgs args = QueryArgs.builder()
                .types("post")
                .statuses("any")
                .search("red car")
                .orderBy("date desc")
                .postIn(1, 2)
                .build();
        assertThat(args.postIn()).isEqualTo(List.of(1, 2));
        assertThat(args.search()).contains("red car");
    }
}
