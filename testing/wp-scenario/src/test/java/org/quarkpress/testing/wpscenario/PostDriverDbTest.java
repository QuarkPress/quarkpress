package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The PHP driver's post operations, run through a real WordPress. */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class PostDriverDbTest {

    static final String OPTIONS = """
            {"id": "driver-post-options", "rule": "POST1", "variant": "subdirectory", "ops": [
              {"op": "site_option", "name": "posts_per_page", "value": "10"},
              {"op": "site_option", "name": "timezone_string", "value": "Mars/Base"},
              {"op": "site_option", "name": "default_ping_status", "value": "closed"},
              {"op": "site_option", "name": "permalink_structure", "value": ""},
              {"op": "insert_post", "as": 0, "fields": {"type": "page", "title": "Front", "status": "publish"}},
              {"op": "read_post", "post": 1},
              {"op": "front_page", "option": "page_for_posts", "post": 1},
              {"op": "front_page", "option": "page_for_posts", "post": 1},
              {"op": "read_options", "site": 0, "names": ["page_for_posts", "page_on_front"]},
              {"op": "trash_post", "as": 0, "post": 1},
              {"op": "read_options", "site": 0, "names": ["page_for_posts"]},
              {"op": "delete_post", "as": 0, "post": 1, "force": true},
              {"op": "trash_post", "as": 0, "post": 1},
              {"op": "untrash_post", "as": 0, "post": 1},
              {"op": "delete_post", "as": 0, "post": 1, "force": true},
              {"op": "stick", "post": 1, "sticky": true},
              {"op": "read_post", "post": 1}
            ]}
            """;

    static final String QUERIES = """
            {"id": "driver-post-queries", "rule": "POST1", "variant": "subdirectory", "ops": [
              {"op": "insert_post", "as": 0, "fields": {"title": "Banana", "status": "publish",
                "date": "2020-01-03 10:00:00"}},
              {"op": "insert_post", "as": 0, "fields": {"title": "apple's pie", "status": "publish",
                "date": "2020-01-01 10:00:00"}},
              {"op": "insert_post", "as": 0, "fields": {"title": "Cherry", "status": "publish",
                "date": "2020-01-02 10:00:00"}},
              {"op": "insert_post", "as": 0, "fields": {"title": "Date", "status": "publish",
                "date": "2020-01-04 10:00:00"}},
              {"op": "stick", "post": 2, "sticky": true},
              {"op": "query_posts", "as": 0, "args": {}},
              {"op": "query_posts", "as": 0, "args": {"orderby": ["title asc"], "per_page": 2, "page": 2}},
              {"op": "query_posts", "as": 0, "args": {"post_in": [3, 1], "orderby": ["post_in asc"]}},
              {"op": "query_posts", "as": 0, "args": {"sticky_first": true, "per_page": 2}},
              {"op": "query_posts", "as": 0, "args": {"search": "apple's"}},
              {"op": "query_posts", "as": 0, "args": {"orderby": ["none asc"], "per_page": -1}},
              {"op": "query_posts", "as": 0, "args": {"orderby": ["menu_order asc"], "per_page": 2}},
              {"op": "query_posts", "as": 0, "args": {"orderby": ["menu_order desc"], "per_page": 2}},
              {"op": "insert_post", "as": 0, "fields": {"title": "Now"}},
              {"op": "site_option", "name": "timezone_string", "value": "Asia/Kolkata"}
            ]}
            """;

    static final String STICKY_TIES = """
            {"id": "driver-sticky-ties", "rule": "POST1", "variant": "subdirectory", "ops": [
              {"op": "insert_post", "as": 0, "fields": {"title": "Alpha", "status": "publish",
                "date": "2020-01-01 10:00:00"}},
              {"op": "insert_post", "as": 0, "fields": {"title": "Zed", "status": "publish",
                "date": "2019-06-01 10:00:00"}},
              {"op": "insert_post", "as": 0, "fields": {"title": "Zulu", "status": "publish",
                "date": "2019-06-01 10:00:00"}},
              {"op": "stick", "post": 2, "sticky": true},
              {"op": "stick", "post": 3, "sticky": true},
              {"op": "query_posts", "as": 0, "args": {"orderby": ["title asc"], "per_page": 1, "sticky_first": true}}
            ]}
            """;

    @Test
    void postOperationsReportWhatWordPressDid() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        Path wordpress = WordPressTree.ensure(System.getProperty("wp.version"));
        try (ScenarioDatabase database = ScenarioDatabase.start()) {
            WordPressScenarioOracle oracle = WordPressScenarioOracle.create(wordpress, database);
            oracle.install(NetworkVariant.SUBDIRECTORY);
            // the installation's sample post, page and privacy policy draft, and the sample comment, are gone
            try (Connection connection = database.connect(NetworkVariant.SUBDIRECTORY.templateDatabase());
                    Statement statement = connection.createStatement()) {
                for (String table : new String[] {"wp_posts", "wp_postmeta", "wp_comments"}) {
                    try (ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).as(table).isZero();
                    }
                }
            }

            Scenario example = ScenarioJson.parse(PostScenarioFormatTest.EXAMPLE);
            Map<String, String> facts =
                    WordPressOutcomes.normalize(example, oracle.run(example)).facts();
            assertThat(facts)
                    .containsEntry("op2:add_user", "ok")
                    .containsEntry("op3:site_option", "changed=true")
                    .containsEntry("op4:insert_post", "created p1")
                    .containsEntry("op5:insert_post", "created p2")
                    .containsEntry("op6:update_post", "ok")
                    .containsEntry("op7:publish_post", "ok")
                    .containsEntry("op8:stick", "ok")
                    .containsEntry("op9:front_page", "changed=true")
                    .containsEntry("op10:trash_post", "done:publish")
                    .containsEntry("op11:untrash_post", "done:trash")
                    // the acting user 0 sees published posts only, and the editor's post is a draft again
                    .containsEntry("op12:query_posts", "(none) found=0")
                    // the published child page has no slug of its own: WordPress gives drafts none
                    .containsEntry("op13:page_uri", "hello/")
                    .containsEntry("op14:read_post:status", "draft")
                    .containsEntry("op14:read_post:slug", "hello")
                    .containsEntry("op14:read_post:author", "u1")
                    .containsEntry("op14:read_post:date", "2020-05-05 10:00:00")
                    .containsEntry("op14:read_post:date_gmt", "2020-05-05 08:00:00")
                    // Paris in summer, both for the given date and for the run's modification
                    .containsEntry("op14:read_post:date_offset", "120")
                    .containsEntry("op14:read_post:modified_gmt", "now")
                    .containsEntry("op14:read_post:modified", "now")
                    .containsEntry("op14:read_post:menu_order", "2")
                    // trashing unsticks
                    .containsEntry("op14:read_post:sticky", "0")
                    .containsEntry("op15:delete_post", "done:publish")
                    .containsEntry("post#1:title", "Hello")
                    .containsEntry("post#1:status", "draft")
                    .containsEntry("post#2:deleted", "yes");
            assertThat(facts.keySet()).noneMatch(key -> key.startsWith("op14:read_post:meta:"));
            // the network's main site has pretty permalinks, so guids are not compared
            assertThat(facts.keySet()).doesNotContain("op14:read_post:guid", "post#1:guid");

            Scenario options = ScenarioJson.parse(OPTIONS);
            assertThat(WordPressOutcomes.normalize(options, oracle.run(options)).facts())
                    // update_option answers whether the stored value changed; an invalid time zone keeps the old one
                    .containsEntry("op1:site_option", "changed=false")
                    .containsEntry("op2:site_option", "changed=false")
                    .containsEntry("op3:site_option", "changed=true")
                    // the network setup gave the main site a pretty structure; plain permalinks give plain guids
                    .containsEntry("op4:site_option", "changed=true")
                    .containsEntry("op5:insert_post", "created p1")
                    .containsEntry("op6:read_post:guid", "home/?page_id=p1")
                    .containsEntry("op6:read_post:author", "none")
                    .containsEntry("op6:read_post:date", "now")
                    .containsEntry("op6:read_post:date_gmt", "now")
                    .containsEntry("op6:read_post:slug", "front")
                    .containsEntry("op7:front_page", "changed=true")
                    .containsEntry("op8:front_page", "changed=false")
                    .containsEntry("op9:read_options:page_for_posts", "p1")
                    .containsEntry("op9:read_options:page_on_front", "none")
                    .containsEntry("op10:trash_post", "done:publish")
                    // trashing the posts page resets the option
                    .containsEntry("op11:read_options:page_for_posts", "none")
                    .containsEntry("op12:delete_post", "done:trash")
                    // a post that no longer exists: WordPress answers nothing, and sticking it still succeeds
                    .containsEntry("op13:trash_post", Outcome.NOTHING)
                    .containsEntry("op14:untrash_post", Outcome.NOTHING)
                    .containsEntry("op15:delete_post", Outcome.NOTHING)
                    .containsEntry("op16:stick", "ok")
                    .containsEntry("op17:read_post:deleted", "yes")
                    .containsEntry("post#1:deleted", "yes");

            Scenario queries = ScenarioJson.parse(QUERIES);
            assertThat(WordPressOutcomes.normalize(queries, oracle.run(queries)).facts())
                    // newest first; a sticky post is not moved without sticky_first
                    .containsEntry("op6:query_posts", "p4,p1,p3,p2 found=4")
                    .containsEntry("op7:query_posts", "p3,p4 found=4")
                    .containsEntry("op8:query_posts", "p3,p1 found=2")
                    // the sticky post, not on the first page, is put before it
                    .containsEntry("op9:query_posts", "p2,p4,p1 found=4")
                    .containsEntry("op10:query_posts", "p2 found=1")
                    .containsEntry("op11:query_posts", "p1,p2,p3,p4 found=4")
                    // equal menu orders: the driver's id order in the key's direction
                    .containsEntry("op12:query_posts", "p1,p2 found=4")
                    .containsEntry("op13:query_posts", "p4,p3 found=4")
                    .containsEntry("op15:site_option", "changed=true")
                    .containsEntry("post#1:date", "2020-01-03 10:00:00")
                    .containsEntry("post#2:sticky", "1")
                    // stamped before the zone changed, still the run
                    .containsEntry("post#5:status", "draft")
                    .containsEntry("post#5:date", "now")
                    .containsEntry("post#5:date_gmt", "zero")
                    .containsEntry("post#5:modified", "now")
                    .containsEntry("post#5:date_offset", "zero")
                    // a new draft without a date copies its zero GMT date into the GMT modification
                    .containsEntry("post#5:modified_offset", "zero")
                    .containsEntry("post#5:modified_gmt", "zero")
                    .containsEntry("post#1:date_offset", "0");

            Scenario ties = ScenarioJson.parse(STICKY_TIES);
            assertThat(WordPressOutcomes.normalize(ties, oracle.run(ties)).facts())
                    // sticky posts outside the page come newest first, equal dates by id descending, whatever the
                    // order of the page itself
                    .containsEntry("op6:query_posts", "p3,p2,p1 found=3");
        }
    }
}
