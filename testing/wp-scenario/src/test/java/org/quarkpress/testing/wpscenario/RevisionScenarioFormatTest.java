package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RevisionScenarioFormatTest {

    static final String EXAMPLE = """
            {"id": "revision-example", "rule": "REV21", "variant": "subdirectory", "revisions_keep": 2, "ops": [
              {"op": "create_user", "username": "ed", "given_name": "", "family_name": ""},
              {"op": "add_user", "user": 1, "site": 0, "role": "editor"},
              {"op": "insert_post", "as": 1, "fields": {"title": "Hello", "content": "Body", "status": "publish"}},
              {"op": "update_post", "as": 1, "post": 1, "fields": {"title": "Hello 2"}, "edit_date": false},
              {"op": "update_post_meta", "as": 1, "post": 1, "key": "footnotes", "value": "[1]"},
              {"op": "autosave", "as": 1, "post": 1, "fields": {"content": "Body 2"}, "meta": {"footnotes": "[2]"}},
              {"op": "autosave", "as": 1, "post": 1, "fields": {"title": "T"}},
              {"op": "revisions", "as": 1, "post": 1, "args": {"order": "asc", "number": 2, "offset": 1,
                "author": 1, "autosaves": "exclude", "check_enabled": false}},
              {"op": "autosave_of", "as": 0, "post": 1, "user": 1},
              {"op": "revision_count", "as": 1, "post": 1},
              {"op": "restore", "as": 1, "revision": "r1", "fields": ["title"]},
              {"op": "delete_revision", "revision": "r2"}
            ]}
            """;

    static final String USER =
            "{\"op\": \"create_user\", \"username\": \"ed\", \"given_name\": \"\", \"family_name\": \"\"},";
    static final String POST = "{\"op\": \"insert_post\", \"as\": 1, \"fields\": {\"title\": \"P\"}},";

    private static String withOps(String ops) {
        return "{\"id\": \"bad\", \"rule\": \"REV1\", \"variant\": \"subdirectory\", \"ops\": [" + ops + "]}";
    }

    @Test
    void everyRevisionOperationIsReadAndWrittenBack() {
        Scenario scenario = ScenarioJson.parse(EXAMPLE);
        assertThat(scenario.revisionsKeep()).isEqualTo(OptionalInt.of(2));
        assertThat(scenario.ops())
                .contains(
                        new ScenarioOp.UpdatePostMeta(1, 1, "footnotes", "[1]"),
                        new ScenarioOp.Autosave(
                                1,
                                1,
                                AutosaveFields.builder().content("Body 2").build(),
                                Optional.of(Map.of("footnotes", "[2]"))),
                        new ScenarioOp.Autosave(
                                1, 1, AutosaveFields.builder().title("T").build(), Optional.empty()),
                        new ScenarioOp.AutosaveOf(0, 1, 1),
                        new ScenarioOp.CountRevisions(1, 1),
                        new ScenarioOp.Restore(1, "r1", List.of("title")),
                        new ScenarioOp.DeleteRevision("r2"));
        ScenarioOp.ListRevisions list =
                (ScenarioOp.ListRevisions) scenario.ops().get(7);
        assertThat(list.args().order()).contains("asc");
        assertThat(list.args().number()).isEqualTo(OptionalInt.of(2));
        assertThat(list.args().offset()).isEqualTo(OptionalInt.of(1));
        assertThat(list.args().author()).isEqualTo(OptionalInt.of(1));
        assertThat(list.args().autosaves()).contains("exclude");
        assertThat(list.args().checkEnabled()).contains(false);
        assertThat(ScenarioJson.parse(ScenarioJson.write(scenario))).isEqualTo(scenario);
        Scenario plain = ScenarioJson.parse(withOps(
                "{\"op\": \"create_user\", \"username\": \"ed\", \"given_name\": \"\", \"family_name\": \"\"}"));
        assertThat(plain.revisionsKeep()).isEmpty();
        assertThat(ScenarioJson.write(plain)).doesNotContain("revisions_keep");
    }

    @Test
    void referencesFieldsAndArgumentsAreChecked() {
        assertThatThrownBy(() -> ScenarioJson.parse(withOps("{\"op\": \"delete_revision\", \"revision\": \"x1\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario revision reference is r<k> (k from 1): x1");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps("{\"op\": \"delete_revision\", \"revision\": \"r0\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario revision reference is r<k> (k from 1): r0");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps(USER
                        + POST
                        + "{\"op\": \"restore\", \"as\": 1, \"revision\": \"r1\", \"fields\": [\"slug\"]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario restores title, content or excerpt: slug");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps(USER
                        + POST
                        + "{\"op\": \"autosave\", \"as\": 1, \"post\": 1, \"fields\": {},"
                        + " \"meta\": {\"color\": \"red\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario's revisioned meta key is footnotes: color");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps(
                        USER + POST + "{\"op\": \"revisions\", \"as\": 0, \"post\": 1, \"args\": {\"offset\": 1}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario lists revisions with an offset only with a number");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps(USER
                        + POST
                        + "{\"op\": \"revisions\", \"as\": 0, \"post\": 1, \"args\": {\"autosaves\": \"all\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario lists autosaves as include, only or exclude: all");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps(
                        USER + "{\"op\": \"autosave\", \"as\": 1, \"post\": 1, \"fields\": {\"title\": \"T\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refers to post #1, but only 0 posts were inserted before it");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps(USER + POST + "{\"op\": \"autosave_of\", \"as\": 0, \"post\": 1, \"user\": 2}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refers to user #2, but only 1 users were created before it");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps(USER
                        + POST
                        + "{\"op\": \"update_post_meta\", \"as\": 1, \"post\": 1,"
                        + " \"key\": \"color\", \"value\": \"x\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario's post meta key is one of footnotes, _wp_trash_meta_time: color");
    }

    @Test
    void aReferenceIsNeverBoundedByTheFormat() {
        Scenario scenario = ScenarioJson.parse(withOps("{\"op\": \"delete_revision\", \"revision\": \"r40\"}"));
        assertThat(scenario.ops()).containsExactly(new ScenarioOp.DeleteRevision("r40"));
    }

    @Test
    void autosaveAndRestoreAreRefusedForTheSystemUser() {
        String create = USER + POST;
        assertThatThrownBy(() -> ScenarioJson.parse(withOps(
                        create + "{\"op\": \"autosave\", \"as\": 0, \"post\": 1, \"fields\": {\"title\": \"T\"}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario autosaves and restores as a created user: WordPress has no system autosave");
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps(create + "{\"op\": \"restore\", \"as\": 0, \"revision\": \"r1\", \"fields\": []}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario autosaves and restores as a created user: WordPress has no system autosave");
    }

    private static void refused(String ops, String message) {
        assertThatThrownBy(() -> ScenarioJson.parse(withOps(ops)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private static String list(String args) {
        return USER + POST + "{\"op\": \"revisions\", \"as\": 0, \"post\": 1, \"args\": " + args + "}";
    }

    @Test
    void listArgumentsAreChecked() {
        refused(list("{\"order\": \"up\"}"), "a scenario lists revisions in asc or desc order: up");
        refused(list("{\"number\": 0}"), "a scenario lists at least 1 revision: 0");
        refused(list("{\"number\": 1, \"offset\": -1}"), "a scenario lists revisions from an offset of 0 or more: -1");
        refused(list("{\"author\": 0}"), "a scenario lists revisions of a created user: author 0 is no filter");
        refused(list("5"), "scenario bad: 'args' must be an object");
    }

    @Test
    void autosaveAndRestoreArgumentsAreChecked() {
        String autosave = USER + POST + "{\"op\": \"autosave\", \"as\": 1, \"post\": 1, ";
        refused(autosave + "\"fields\": 5}", "scenario bad: 'fields' must be an object");
        refused(autosave + "\"fields\": {}, \"meta\": 5}", "scenario bad: 'meta' must be an object");
        refused(autosave + "\"fields\": {}, \"meta\": {\"footnotes\": 5}}", "scenario bad: 'meta' holds only text");
        refused(
                autosave + "\"fields\": {\"title\": \"a\\u0001\"}}",
                "a scenario post text holds no control characters but tab and line feed, and no surrogates");
        refused(
                USER + POST + "{\"op\": \"restore\", \"as\": 1, \"revision\": \"r1\","
                        + " \"fields\": [\"title\", \"title\"]}",
                "a scenario restores a field once: title");
    }
}
