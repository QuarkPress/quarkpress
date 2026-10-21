package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wporacle.OracleJson;

class TermOutcomesTest {

    static final String SCENARIO = """
            {"id": "terms-normalize", "rule": "TAX35", "variant": "subdirectory", "ops": [
              {"op": "create_user", "username": "ed", "given_name": "", "family_name": ""},
              {"op": "insert_post", "as": 1, "fields": {"title": "Hello", "status": "publish"}},
              {"op": "insert_term", "as": 1, "taxonomy": "category", "fields": {"name": "World"}},
              {"op": "insert_term", "as": 1, "taxonomy": "post_tag", "fields": {"name": "!!!"}},
              {"op": "insert_term", "as": 1, "taxonomy": "post_tag", "fields": {"name": "World"}},
              {"op": "set_post_terms", "as": 1, "post": 1, "taxonomy": "category",
                "terms": [{"term": "t1"}, {"name": "Fresh"}, {"term": "default"}], "append": false},
              {"op": "post_terms", "post": 1, "taxonomy": "category"},
              {"op": "query_terms", "as": 0, "args": {"taxonomies": ["category"], "orderby": "none",
                "pad_counts": true}},
              {"op": "delete_term", "as": 1, "term": "default", "taxonomy": "category"},
              {"op": "get_term", "term": "t2", "taxonomy": ""},
              {"op": "term_exists", "taxonomy": "post_tag", "value": "nothing"}
            ]}
            """;

    static final String DRIVER_OUT = """
            {"main": 1, "installer": 1, "home": "http://localhost", "started": 1792490400, "finished": 1792490405,
             "results": [
              {"op": "create_user", "id": 5, "nicename": "ed", "display_name": "ed"},
              {"op": "insert_post", "id": 40},
              {"op": "insert_term", "id": 7},
              {"op": "insert_term", "id": 8},
              {"op": "insert_term", "error": "term_exists"},
              {"op": "set_post_terms", "ids": [7, 9, 1]},
              {"op": "post_terms", "ids": [9, 1, 7], "counts": [1, 1, 1]},
              {"op": "query_terms", "ids": [9, 7, 1], "counts": [1, 1, 2]},
              {"op": "delete_term", "result": "kept"},
              {"op": "get_term", "term": {"id": 8, "taxonomy": "post_tag", "name": "!!!", "slug": "8",
                "description": "", "parent": 0, "count": 0}},
              {"op": "term_exists", "id": 0}
             ], "sites": [], "posts": [],
             "terms": [
              {"index": 1, "id": 7, "term": {"id": 7, "taxonomy": "category", "name": "World", "slug": "world",
                "description": "", "parent": 0, "count": 1}},
              {"index": 2, "id": 8, "term": {"id": 8, "taxonomy": "post_tag", "name": "!!!", "slug": "8",
                "description": "", "parent": 0, "count": 0}},
              {"index": 3, "id": 9, "term": {"deleted": true}}
             ],
             "term_defaults": {
              "category": {"id": 1, "term": {"id": 1, "taxonomy": "category", "name": "Uncategorized",
                "slug": "uncategorized", "description": "", "parent": 0, "count": 1}},
              "genre": {"id": 2, "term": {"id": 2, "taxonomy": "genre", "name": "General", "slug": "general",
                "description": "", "parent": 0, "count": 1}}}}
            """;

    @Test
    void termObservationsAreNormalized() throws Exception {
        Scenario scenario = ScenarioJson.parse(SCENARIO);
        JsonNode out = OracleJson.MAPPER.readTree(DRIVER_OUT);
        Map<String, String> facts = WordPressOutcomes.normalize(scenario, out).facts();
        assertThat(facts)
                .containsEntry("op3:insert_term", "created t1")
                .containsEntry("op4:insert_term", "created t2")
                .containsEntry("op5:insert_term", "refused:term_exists")
                .containsEntry("op6:set_post_terms", "t1,t3,default-category")
                .containsEntry("op7:post_terms", "t3=1,default-category=1,t1=1")
                .containsEntry("op8:query_terms", "default-category=2,t1=1,t3=1")
                .containsEntry("op9:delete_term", "kept")
                .containsEntry("op10:get_term:slug", "#self")
                .containsEntry("op11:term_exists", "none")
                .containsEntry("term#t1:name", "World")
                .containsEntry("term#t2:slug", "#self")
                .containsEntry("term#t3:deleted", "yes")
                .containsEntry("term#default-category:count", "1")
                .containsEntry("term#default-genre:name", "General");
    }

    static final String SCENARIO_2 = """
            {"id": "terms-answers", "rule": "TAX35", "variant": "subdirectory", "ops": [
              {"op": "create_user", "username": "ed", "given_name": "", "family_name": ""},
              {"op": "insert_post", "as": 1, "fields": {"title": "Hello", "status": "publish"}},
              {"op": "insert_term", "as": 1, "taxonomy": "category", "fields": {"name": "Alpha"}},
              {"op": "update_term", "as": 1, "term": "t1", "taxonomy": "category", "fields": {"description": "x"}},
              {"op": "remove_post_terms", "as": 1, "post": 1, "taxonomy": "category", "terms": [{"term": "t1"}]},
              {"op": "get_term", "term": "t1", "taxonomy": "post_tag"},
              {"op": "term_exists", "taxonomy": "category", "value": "Alpha"},
              {"op": "query_terms", "as": 0, "args": {"taxonomies": ["category"], "orderby": "name"}},
              {"op": "delete_term", "as": 1, "term": "t1", "taxonomy": "category"},
              {"op": "post_terms", "post": 1, "taxonomy": "genre"},
              {"op": "remove_post_terms", "as": 1, "post": 1, "taxonomy": "category", "terms": [{"name": "Zed"}]}
            ]}
            """;

    static final String DRIVER_OUT_2 = """
            {"main": 1, "installer": 1, "home": "http://localhost", "started": 1792490400, "finished": 1792490405,
             "results": [
              {"op": "create_user", "id": 5, "nicename": "ed", "display_name": "ed"},
              {"op": "insert_post", "id": 40},
              {"op": "insert_term", "id": 20},
              {"op": "update_term", "ok": true},
              {"op": "remove_post_terms", "removed": true},
              {"op": "get_term", "term": null},
              {"op": "term_exists", "id": 20},
              {"op": "query_terms", "ids": [20, 1], "counts": [0, 1]},
              {"op": "delete_term", "missing": true},
              {"op": "post_terms", "ids": [2], "counts": [1]},
              {"op": "remove_post_terms", "removed": false}
             ], "sites": [], "posts": [],
             "terms": [
              {"index": 1, "id": 20, "term": {"id": 20, "taxonomy": "category", "name": "Alpha", "slug": "alpha",
                "description": "x", "parent": 0, "count": 0}}
             ],
             "term_defaults": {
              "category": {"id": 1, "term": {"id": 1, "taxonomy": "category", "name": "Uncategorized",
                "slug": "uncategorized", "description": "", "parent": 0, "count": 1}},
              "genre": {"id": 2, "term": {"deleted": true}}}}
            """;

    @Test
    void everyAnswerOfTheDriverIsRecorded() throws Exception {
        Scenario scenario = ScenarioJson.parse(SCENARIO_2);
        JsonNode out = OracleJson.MAPPER.readTree(DRIVER_OUT_2);
        Map<String, String> facts = WordPressOutcomes.normalize(scenario, out).facts();
        assertThat(facts)
                .containsEntry("op3:insert_term", "created t1")
                .containsEntry("op4:update_term", "ok")
                .containsEntry("op5:remove_post_terms", "removed=true")
                .containsEntry("op6:get_term", "none")
                .containsEntry("op7:term_exists", "t1")
                .containsEntry("op8:query_terms", "t1=0,default-category=1")
                .containsEntry("op9:delete_term", "missing")
                .containsEntry("op10:post_terms", "default-genre=1")
                .containsEntry("op11:remove_post_terms", "removed=false")
                .containsEntry("term#t1:description", "x")
                .containsEntry("term#t1:parent", "none")
                .containsEntry("term#default-genre:deleted", "yes");
        assertThat(facts).doesNotContainKey("op6:get_term:name");
    }
}
