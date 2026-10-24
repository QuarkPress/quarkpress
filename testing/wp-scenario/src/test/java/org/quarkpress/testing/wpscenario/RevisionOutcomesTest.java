package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wporacle.OracleJson;

class RevisionOutcomesTest {

    static final String SCENARIO = """
            {"id": "revisions-normalize", "rule": "REV21", "variant": "subdirectory", "ops": [
              {"op": "create_user", "username": "ed", "given_name": "", "family_name": ""},
              {"op": "create_user", "username": "au", "given_name": "", "family_name": ""},
              {"op": "insert_post", "as": 1, "fields": {"title": "Hello", "content": "Body", "status": "publish"}},
              {"op": "insert_post", "as": 2, "fields": {"title": "Draft", "status": "draft"}},
              {"op": "update_post", "as": 1, "post": 1, "fields": {"title": "Hello 2"}, "edit_date": false},
              {"op": "update_post_meta", "as": 1, "post": 1, "key": "footnotes", "value": "[1]"},
              {"op": "autosave", "as": 2, "post": 1, "fields": {"content": "Body 2"}},
              {"op": "autosave", "as": 2, "post": 2, "fields": {"content": "xy"}},
              {"op": "revisions", "as": 1, "post": 1, "args": {}},
              {"op": "autosave_of", "as": 0, "post": 1, "user": 0},
              {"op": "revision_count", "as": 1, "post": 1},
              {"op": "restore", "as": 1, "revision": "r1", "fields": []},
              {"op": "delete_revision", "revision": "r2"},
              {"op": "revisions", "as": 2, "post": 1, "args": {}},
              {"op": "revision_count", "as": 1, "post": 2},
              {"op": "autosave_of", "as": 1, "post": 1, "user": 2},
              {"op": "restore", "as": 1, "revision": "r3", "fields": []},
              {"op": "update_post_meta", "as": 1, "post": 1, "key": "footnotes", "value": "x"},
              {"op": "revisions", "as": 1, "post": 1, "args": {}}
            ]}
            """;

    static final String DRIVER_OUT = """
            {"main": 1, "installer": 1, "home": "http://localhost", "started": 1792490400, "finished": 1792490405,
             "results": [
              {"op": "create_user", "id": 5, "nicename": "ed", "display_name": "ed"},
              {"op": "create_user", "id": 6, "nicename": "au", "display_name": "au"},
              {"op": "insert_post", "id": 40},
              {"op": "insert_post", "id": 41},
              {"op": "update_post", "ok": true},
              {"op": "update_post_meta", "changed": true},
              {"op": "autosave", "id": 43, "parent": 40},
              {"op": "autosave", "id": 41, "parent": 0},
              {"op": "revisions", "ids": [43, 42]},
              {"op": "autosave_of", "id": 43},
              {"op": "revision_count", "latest": 43, "count": 2},
              {"op": "restore", "ok": true},
              {"op": "delete_revision", "result": "deleted"},
              {"op": "revisions", "error": "forbidden"},
              {"op": "revision_count", "latest": 0, "count": 0},
              {"op": "autosave_of", "id": 0},
              {"op": "restore", "result": "nothing"},
              {"op": "update_post_meta", "missing": true},
              {"op": "revisions", "missing": true}
             ], "sites": [], "posts": [], "terms": [], "term_defaults": {},
             "revisions": [
              {"index": 1, "id": 42, "revision": {"id": 42, "parent": 40, "author": 5, "autosave": false,
                "title": "Hello 2", "content": "Body", "excerpt": "", "footnotes": [],
                "date_gmt": "2026-10-22 10:00:01"}},
              {"index": 2, "id": 43, "revision": {"deleted": true}},
              {"index": 3, "id": 44, "revision": {"id": 44, "parent": 40, "author": 5, "autosave": false,
                "title": "Hello 2", "content": "Body", "excerpt": "", "footnotes": ["[1]"],
                "date_gmt": "2026-10-22 10:00:03"}}
             ]}
            """;

    @Test
    void revisionObservationsAreNormalized() throws Exception {
        Scenario scenario = ScenarioJson.parse(SCENARIO);
        JsonNode out = OracleJson.MAPPER.readTree(DRIVER_OUT);
        Map<String, String> facts = WordPressOutcomes.normalize(scenario, out).facts();
        assertThat(facts)
                .containsEntry("op6:update_post_meta", "changed=true")
                .containsEntry("op7:autosave", "r2")
                .containsEntry("op8:autosave", "p2")
                .containsEntry("op9:revisions", "r2,r1")
                .containsEntry("op10:autosave_of", "r2")
                .containsEntry("op11:revision_count", "latest=r2 count=2")
                .containsEntry("op12:restore", "ok")
                .containsEntry("op13:delete_revision", "deleted")
                .containsEntry("op14:revisions", "refused:forbidden")
                .containsEntry("op15:revision_count", "latest=none count=0")
                .containsEntry("op16:autosave_of", "none")
                .containsEntry("op17:restore", "nothing")
                .containsEntry("op18:update_post_meta", "missing")
                .containsEntry("op19:revisions", "missing")
                .containsEntry("revision#r1:parent", "p1")
                .containsEntry("revision#r1:author", "u1")
                .containsEntry("revision#r1:autosave", "0")
                .containsEntry("revision#r1:footnotes", "(none)")
                .containsEntry("revision#r2:deleted", "yes")
                .containsEntry("revision#r3:footnotes", "[1]");
        // the fixture's dates lie outside the run window (started/finished are in 2026-10-20), so they show as written
        assertThat(facts).containsEntry("revision#r1:date_gmt", "2026-10-22 10:00:01");
    }
}
