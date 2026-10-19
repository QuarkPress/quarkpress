package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wporacle.OracleJson;

class CapabilityScenarioFormatTest {

    static final String EXAMPLE = """
            {"id": "cap-example", "rule": "CAP21", "variant": "subdirectory", "ops": [
              {"op": "create_site", "domain": "localhost", "path": "/shop/", "title": "Shop"},
              {"op": "create_user", "username": "ann.lee", "given_name": "Ann", "family_name": "Lee"},
              {"op": "create_user", "username": "bob", "given_name": "", "family_name": ""},
              {"op": "add_user", "user": 1, "site": 1, "role": "administrator"},
              {"op": "set_role", "user": 2, "site": 1, "role": "author"},
              {"op": "add_cap", "user": 2, "site": 1, "capability": "upload_files", "grant": false},
              {"op": "super_admin", "user": 2, "grant": true},
              {"op": "network_flag", "name": "add_new_users", "value": true},
              {"op": "site_setting", "site": 1, "name": "link_manager_enabled", "value": true},
              {"op": "add_role", "site": 1, "role": "shop_manager", "name": "Shop manager", "capabilities": ["read"]},
              {"op": "role_cap", "site": 1, "role": "editor", "capability": "edit_theme_options", "grant": true},
              {"op": "remove_role", "site": 1, "role": "contributor"},
              {"op": "remove_user", "user": 2, "site": 1},
              {"op": "can", "user": 1, "site": 1, "capability": "edit_user", "target": 2},
              {"op": "can", "user": 0, "site": 0, "capability": "exist", "target": 0}
            ]}
            """;

    private static String withOps(String ops) {
        return "{\"id\": \"bad\", \"rule\": \"CAP1\", \"variant\": \"subdirectory\", \"ops\": [" + ops + "]}";
    }

    @Test
    void everyCapabilityOperationIsReadAndWrittenBack() {
        Scenario scenario = ScenarioJson.parse(EXAMPLE);
        assertThat(scenario.ops())
                .contains(
                        new ScenarioOp.CreateUser("ann.lee", "Ann", "Lee"),
                        new ScenarioOp.AddUser(1, 1, "administrator"),
                        new ScenarioOp.AddCap(2, 1, "upload_files", false),
                        new ScenarioOp.SuperAdmin(2, true),
                        new ScenarioOp.NetworkFlag("add_new_users", true),
                        new ScenarioOp.SiteSetting(1, "link_manager_enabled", true),
                        new ScenarioOp.AddRole(1, "shop_manager", "Shop manager", List.of("read")),
                        new ScenarioOp.RoleCap(1, "editor", "edit_theme_options", true),
                        new ScenarioOp.RemoveRole(1, "contributor"),
                        new ScenarioOp.RemoveUser(2, 1),
                        new ScenarioOp.Can(1, 1, "edit_user", 2),
                        new ScenarioOp.Can(0, 0, "exist", 0));
        assertThat(ScenarioJson.parse(ScenarioJson.write(scenario))).isEqualTo(scenario);
    }

    @Test
    void referencesMustNameEarlierUsersAndSites() {
        assertThatThrownBy(() -> ScenarioJson.parse(
                        withOps("{\"op\": \"add_user\", \"user\": 1, \"site\": 0," + " \"role\": \"editor\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 1 refers to user #1, but only 0 users were created before it");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps("{\"op\": \"create_user\", \"username\": \"ann\","
                        + " \"given_name\": \"\", \"family_name\": \"\"}, {\"op\": \"can\", \"user\": 1, \"site\": 2,"
                        + " \"capability\": \"read\", \"target\": 0}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 2 refers to site #2, but only 0 sites were created before it");
    }

    @Test
    void inputsBothSidesCannotCompareAreRefused() {
        assertThatThrownBy(() -> new ScenarioOp.CreateUser("Admin", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the username admin belongs to the WordPress installation's own user");
        assertThatThrownBy(() -> new ScenarioOp.CreateUser("ann  lee", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a user without names needs a username WordPress keeps as it is: ASCII letters,"
                        + " digits, _ . - @ and single inner spaces: ann  lee");
        assertThatThrownBy(() -> new ScenarioOp.CreateUser("ann", "Tom & Jerry", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario name holds no HTML-special characters, no control characters and no"
                        + " surrounding or doubled spaces: Tom & Jerry");
        assertThatThrownBy(() -> new ScenarioOp.NetworkFlag("registration", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario network flag is add_new_users or menu_plugins: registration");
        assertThatThrownBy(() -> new ScenarioOp.AddCap(1, 0, "edit posts", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a scenario capability or role key holds only letters, digits, _ and -: edit posts");
        String accented = (char) 0xC9 + "milie";
        assertThat(new ScenarioOp.CreateUser(accented, accented, "Dubois").username())
                .isEqualTo(accented);
    }

    @Test
    void driverAnswersAreNormalized() throws Exception {
        Scenario scenario = ScenarioJson.parse(EXAMPLE);
        JsonNode out = OracleJson.MAPPER.readTree("""
                {"main": 1, "results": [
                  {"op": "create_site", "id": 4},
                  {"op": "create_user", "id": 2, "nicename": "ann-lee", "display_name": "Ann Lee"},
                  {"op": "create_user", "error": "existing_user_login"},
                  {"op": "add_user", "ok": true},
                  {"op": "set_role", "missing": true},
                  {"op": "add_cap", "missing": true},
                  {"op": "super_admin", "missing": true},
                  {"op": "network_flag", "ok": true},
                  {"op": "site_setting", "ok": true},
                  {"op": "add_role", "changed": true},
                  {"op": "role_cap", "ok": false},
                  {"op": "remove_role", "ok": true},
                  {"op": "remove_user", "missing": true},
                  {"op": "can", "can": false},
                  {"op": "can", "can": true}
                ], "sites": [
                  {"index": 1, "id": 4, "domain": "localhost", "path": "/shop/", "public": 1, "archived": 0,
                   "mature": 0, "spam": 0, "deleted": 0}
                ]}
                """);
        Map<String, String> facts = WordPressOutcomes.normalize(scenario, out).facts();
        assertThat(facts)
                .containsEntry("op2:create_user", "nicename=ann-lee display=Ann Lee")
                .containsEntry("op3:create_user", "refused:existing_user_login")
                .containsEntry("op4:add_user", "ok")
                .containsEntry("op5:set_role", "missing")
                .containsEntry("op7:super_admin", "missing")
                .containsEntry("op8:network_flag", "ok")
                .containsEntry("op10:add_role", "changed=true")
                .containsEntry("op11:role_cap", "failed")
                .containsEntry("op14:can", "no")
                .containsEntry("op15:can", "yes");
        assertThat(Outcome.nicename("user-42", 42, 3)).isEqualTo("user-#3");
        assertThat(Outcome.nicename("user-42-2", 42, 3)).isEqualTo("user-#3-2");
        assertThat(Outcome.nicename("user-4", 42, 3)).isEqualTo("user-4");
    }

    @Test
    void siteAndUserReferencesAreChecked() {
        assertThatThrownBy(() -> ScenarioJson.parse(withOps("{\"op\": \"create_user\", \"username\": \"ann\","
                        + " \"given_name\": \"\", \"family_name\": \"\"}, {\"op\": \"add_user\", \"user\": 1,"
                        + " \"site\": -1, \"role\": \"editor\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 2 refers to site #-1; sites count from 0");
        assertThatThrownBy(() -> ScenarioJson.parse(withOps("{\"op\": \"create_user\", \"username\": \"ann\","
                        + " \"given_name\": \"\", \"family_name\": \"\"}, {\"op\": \"set_role\", \"user\": 0,"
                        + " \"site\": 0, \"role\": \"editor\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario bad: op 2 refers to user #0; users count from 1 (user 0, a logged-out visitor,"
                        + " is only valid in can)");
    }

    @Test
    void usernamesWordPressWouldChangeOrQuarkPressWouldRenameAreRefused() {
        for (String username : List.of("a%41b", "a<b>c", "a&amp;b", " ann", "user-5", "User 5-2")) {
            assertThatThrownBy(() -> new ScenarioOp.CreateUser(username, "Ann", ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new ScenarioOp.CreateUser("user-x", "Ann", "").username()).isEqualTo("user-x");
    }

    @Test
    void anErrorCodeIsKeptInTheOutcome() throws Exception {
        Scenario scenario = ScenarioJson.parse(withOps("{\"op\": \"create_user\", \"username\": \"ann\","
                + " \"given_name\": \"\", \"family_name\": \"\"}, {\"op\": \"add_user\", \"user\": 1,"
                + " \"site\": 0, \"role\": \"nope\"}"));
        JsonNode out = OracleJson.MAPPER.readTree(
                "{\"main\": 1, \"results\": [{\"op\": \"create_user\", \"id\": 2, \"nicename\": \"ann\","
                        + " \"display_name\": \"ann\"}, {\"op\": \"add_user\", \"ok\": false, \"error\":"
                        + " \"invalid_role\"}], \"sites\": []}");
        assertThat(WordPressOutcomes.normalize(scenario, out).facts())
                .containsEntry("op2:add_user", "failed:invalid_role");
    }
}
