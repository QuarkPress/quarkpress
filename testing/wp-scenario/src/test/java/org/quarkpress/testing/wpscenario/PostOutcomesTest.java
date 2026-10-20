package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wporacle.OracleJson;

class PostOutcomesTest {

    static final String SCENARIO = """
            {"id": "posts-normalize", "rule": "POST7", "variant": "subdirectory", "ops": [
              {"op": "site_option", "name": "permalink_structure", "value": ""},
              {"op": "create_user", "username": "ed", "given_name": "", "family_name": ""},
              {"op": "insert_post", "as": 1, "fields": {"title": "Hello", "status": "publish"}},
              {"op": "insert_post", "as": 0, "fields": {"title": ""}},
              {"op": "trash_post", "as": 0, "post": 1},
              {"op": "query_posts", "as": 0, "args": {"statuses": ["trash"]}},
              {"op": "read_post", "post": 1}
            ]}
            """;

    static final String RECORD = """
            {"id": 40, "type": "post", "status": "trash", "slug": "hello__trashed", "title": "Hello", "content": "",
             "excerpt": "", "author": 5, "parent": 0, "menu_order": 0, "date": "2026-10-20 10:00:02",
             "date_gmt": "2026-10-20 10:00:02", "modified": "2026-10-20 10:00:03",
             "modified_gmt": "2026-10-20 10:00:03", "comment_status": "open",
             "ping_status": "open", "password": "", "sticky": false, "guid": "http://localhost/?p=40",
             "meta": {"_wp_trash_meta_status": ["publish"], "_wp_trash_meta_time": ["1792490403"],
                      "_wp_desired_post_slug": ["hello"]}}
            """;

    static final String DRIVER_OUT = """
            {"main": 1, "installer": 1, "home": "http://localhost", "started": 1792490400, "finished": 1792490405,
             "results": [
              {"op": "site_option", "changed": true},
              {"op": "create_user", "id": 5, "nicename": "ed", "display_name": "ed"},
              {"op": "insert_post", "id": 40},
              {"op": "insert_post", "error": "empty_content"},
              {"op": "trash_post", "result": "done:publish"},
              {"op": "query_posts", "ids": [40], "found": 1},
              {"op": "read_post", "post": %s}
             ], "sites": [], "posts": [{"index": 1, "post": %s}]}
            """.formatted(RECORD, RECORD);

    @Test
    void postObservationsAreNormalized() throws Exception {
        Scenario scenario = ScenarioJson.parse(SCENARIO);
        JsonNode out = OracleJson.MAPPER.readTree(DRIVER_OUT);
        Map<String, String> facts = WordPressOutcomes.normalize(scenario, out).facts();
        assertThat(facts)
                .containsEntry("op3:insert_post", "created p1")
                .containsEntry("op4:insert_post", "refused:empty_content")
                .containsEntry("op5:trash_post", "done:publish")
                .containsEntry("op6:query_posts", "p1 found=1")
                .containsEntry("op7:read_post:slug", "hello__trashed")
                .containsEntry("post#1:author", "u1")
                .containsEntry("post#1:date", "now")
                .containsEntry("post#1:date_gmt", "now")
                .containsEntry("post#1:modified", "now")
                .containsEntry("post#1:sticky", "0")
                .containsEntry("post#1:guid", "home/?p=p1")
                .containsEntry("post#1:meta:_wp_trash_meta_time", "now")
                .containsEntry("post#1:meta:_wp_desired_post_slug", "hello");
        assertThat(facts.keySet()).doesNotContain("post#2:status");
    }

    static final String ARMS = """
            {"id": "posts-arms", "rule": "POST1", "variant": "subdirectory", "ops": [
              {"op": "site_option", "name": "permalink_structure", "value": "/%postname%/"},
              {"op": "insert_post", "as": 0, "fields": {"type": "page", "title": "", "status": "publish"}},
              {"op": "front_page", "option": "page_on_front", "post": 1},
              {"op": "update_post", "as": 0, "post": 1, "fields": {"content": "x"}, "edit_date": false},
              {"op": "publish_post", "post": 1},
              {"op": "stick", "post": 1, "sticky": false},
              {"op": "read_options", "site": 0, "names": ["page_on_front", "page_for_posts", "show_on_front"]},
              {"op": "page_uri", "post": 1},
              {"op": "read_post", "post": 1},
              {"op": "delete_post", "as": 0, "post": 1, "force": true},
              {"op": "trash_post", "as": 0, "post": 1},
              {"op": "update_post", "as": 0, "post": 1, "fields": {}, "edit_date": false},
              {"op": "insert_post", "as": 0, "fields": {"parent": 1}}
            ]}
            """;

    static final String PAGE = """
            {"id": 41, "type": "page", "status": "publish", "slug": "41-2", "title": "", "content": "x",
             "excerpt": "", "author": 0, "parent": 0, "menu_order": 0, "date": "1999-12-31 23:00:00",
             "date_gmt": "1999-12-31 23:00:00",
             "modified": "2090-01-01 00:00:00", "modified_gmt": "2090-01-01 00:00:00", "comment_status": "closed",
             "ping_status": "closed", "password": "", "sticky": false, "guid": "http://localhost/41-2/", "meta": {}}
            """;

    static final String ARMS_OUT = """
            {"main": 1, "installer": 1, "home": "http://localhost", "started": 1792490400, "finished": 1792490405,
             "results": [
              {"op": "site_option", "changed": true},
              {"op": "insert_post", "id": 41},
              {"op": "front_page", "changed": false},
              {"op": "update_post", "ok": true},
              {"op": "publish_post", "ok": true},
              {"op": "stick", "ok": true},
              {"op": "read_options", "values": {"page_on_front": "41", "page_for_posts": "0", "show_on_front": "page"}},
              {"op": "page_uri", "uri": "41-2"},
              {"op": "read_post", "post": %s},
              {"op": "delete_post", "result": "done:publish"},
              {"op": "trash_post", "result": "nothing"},
              {"op": "update_post", "error": "invalid_post"},
              {"op": "insert_post", "error": "invalid_parent"}
             ], "sites": [], "posts": [{"index": 1, "post": {"deleted": true}}]}
            """.formatted(PAGE);

    @Test
    void everyPostOperationHasItsOwnAnswer() throws Exception {
        Scenario scenario = ScenarioJson.parse(ARMS);
        Map<String, String> facts = WordPressOutcomes.normalize(scenario, OracleJson.MAPPER.readTree(ARMS_OUT))
                .facts();
        assertThat(facts)
                .containsEntry("op1:site_option", "changed=true")
                .containsEntry("op2:insert_post", "created p1")
                .containsEntry("op3:front_page", "changed=false")
                .containsEntry("op4:update_post", "ok")
                .containsEntry("op5:publish_post", "ok")
                .containsEntry("op6:stick", "ok")
                .containsEntry("op7:read_options:page_on_front", "p1")
                .containsEntry("op7:read_options:page_for_posts", "none")
                .containsEntry("op7:read_options:show_on_front", "page")
                .containsEntry("op8:page_uri", "#p1-2")
                .containsEntry("op9:read_post:slug", "#self-2")
                .containsEntry("op9:read_post:date", "1999-12-31 23:00:00")
                .containsEntry("op9:read_post:modified", "2090-01-01 00:00:00")
                .containsEntry("op9:read_post:modified_gmt", "2090-01-01 00:00:00")
                .containsEntry("op9:read_post:date_offset", "0")
                .containsEntry("op9:read_post:modified_offset", "0")
                .containsEntry("op10:delete_post", "done:publish")
                .containsEntry("op11:trash_post", Outcome.NOTHING)
                .containsEntry("op12:update_post", "refused:invalid_post")
                .containsEntry("op13:insert_post", "refused:invalid_parent")
                .containsEntry("post#1:deleted", "yes");
        // a pretty permalink is not compared: QuarkPress stores the plain form
        assertThat(facts.keySet()).doesNotContain("op9:read_post:guid");
    }

    @Test
    void anEmptyQueryAndAMissingReferenceAreNamed() throws Exception {
        Scenario scenario = ScenarioJson.parse("""
                {"id": "posts-empty", "rule": "POST1", "variant": "subdirectory", "ops": [
                  {"op": "query_posts", "as": 0, "args": {}},
                  {"op": "create_user", "username": "ed", "given_name": "", "family_name": ""},
                  {"op": "insert_post", "as": 1, "fields": {}},
                  {"op": "query_posts", "as": 0, "args": {"orderby": ["none asc"], "per_page": -1}}
                ]}
                """);
        JsonNode out = OracleJson.MAPPER.readTree("""
                {"main": 1, "installer": 1, "home": "http://localhost", "started": 1792490400,
                 "finished": 1792490405, "results": [
                  {"op": "query_posts", "ids": [], "found": 0},
                  {"op": "create_user", "error": "existing_user_login"},
                  {"op": "insert_post", "missing": true},
                  {"op": "query_posts", "ids": [9, 8], "found": 2}
                 ], "sites": [], "posts": []}
                """);
        assertThat(WordPressOutcomes.normalize(scenario, out).facts())
                .containsEntry("op1:query_posts", "(none) found=0")
                .containsEntry("op3:insert_post", "missing")
                // no order: the posts as a sorted set
                .containsEntry("op4:query_posts", "#?8,#?9 found=2");
    }

    @Test
    void aZoneChangedAfterAnInsertStillShowsTheRunAsNow() throws Exception {
        Scenario scenario = ScenarioJson.parse("""
                {"id": "posts-zone", "rule": "POST14", "variant": "subdirectory", "ops": [
                  {"op": "site_option", "name": "timezone_string", "value": "Europe/Paris"},
                  {"op": "insert_post", "as": 0, "fields": {"title": "Now", "status": "publish"}},
                  {"op": "update_post", "as": 0, "post": 1, "fields": {"date": "2020-01-01 00:00:00"},
                    "edit_date": false},
                  {"op": "site_option", "name": "gmt_offset", "value": "5.5"},
                  {"op": "site_option", "name": "timezone_string", "value": ""}
                ]}
                """);
        // stamped at 10:00:02 UTC in Paris (12:00:02); read back after the zone became +05:30, the local texts
        // convert to an instant hours away from the run
        JsonNode out = OracleJson.MAPPER.readTree("""
                {"main": 1, "installer": 1, "home": "http://localhost", "started": 1792490400,
                 "finished": 1792490405, "results": [
                  {"op": "site_option", "changed": true},
                  {"op": "insert_post", "id": 4},
                  {"op": "update_post", "ok": true},
                  {"op": "site_option", "changed": true},
                  {"op": "site_option", "changed": true}
                 ], "sites": [], "posts": [{"index": 1, "post": {"id": 4, "type": "post", "status": "publish",
                   "slug": "now", "title": "Now", "content": "", "excerpt": "", "author": 0, "parent": 0,
                   "menu_order": 0, "date": "2020-01-01 00:00:00", "date_gmt": "2019-12-31 23:00:00",
                   "modified": "2026-10-20 12:00:03", "modified_gmt": "2026-10-20 10:00:03", "comment_status": "open",
                   "ping_status": "open",
                   "password": "", "sticky": false, "guid": "", "meta": {"_wp_old_date": ["2026-10-20"]}}}]}
                """);
        assertThat(WordPressOutcomes.normalize(scenario, out).facts())
                .containsEntry("post#1:date", "2020-01-01 00:00:00")
                .containsEntry("post#1:date_gmt", "2019-12-31 23:00:00")
                .containsEntry("post#1:modified", "now")
                // the offsets of the local texts are compared, so a zone slip on a run stamp still shows
                .containsEntry("post#1:date_offset", "60")
                .containsEntry("post#1:modified_gmt", "now")
                .containsEntry("post#1:modified_offset", "120")
                .containsEntry("post#1:meta:_wp_old_date", "now");
    }
}
