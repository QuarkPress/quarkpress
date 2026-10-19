package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.RoleDefinition;

class RoleTablesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void registeredRolesFollowTheBuiltInOnesInKeyOrderAndGrantsAppend() {
        List<RoleDefinition> table = RoleTables.defaults(
                BuiltInRoles.all(),
                List.of(
                        RoleDefinition.of("zeta", "Zeta", List.of("read")),
                        RoleDefinition.of("alpha", "Alpha", List.of())),
                Map.of("manage_shop", Set.of("alpha", "editor")));
        assertThat(table)
                .extracting(RoleDefinition::key)
                .containsExactly("administrator", "editor", "author", "contributor", "subscriber", "alpha", "zeta");
        assertThat(RoleTables.byKey(table).get("alpha").capabilities()).containsExactly(Map.entry("manage_shop", true));
        assertThat(List.copyOf(
                        RoleTables.byKey(table).get("editor").capabilities().keySet()))
                .endsWith("manage_shop")
                .hasSize(35);
    }

    @Test
    void aDuplicateRoleOrAGrantToAnUnknownRoleIsRefused() {
        assertThatThrownBy(() -> RoleTables.defaults(
                        BuiltInRoles.all(), List.of(RoleDefinition.of("editor", "Chief", List.of())), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the role editor is registered twice");
        assertThatThrownBy(() -> RoleTables.defaults(BuiltInRoles.all(), List.of(), Map.of("x", Set.of("nobody"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the capability x is granted to the unknown role nobody");
    }

    @Test
    void twoRegisteredRolesWithOneKeyAreRefused() {
        assertThatThrownBy(() -> RoleTables.defaults(
                        BuiltInRoles.all(),
                        List.of(
                                RoleDefinition.of("shop", "Shop", List.of()),
                                RoleDefinition.of("shop", "Store", List.of())),
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the role shop is registered twice");
    }

    @Test
    void theKeySuperAdminIsReservedForNetworkAdministrators() {
        assertThatThrownBy(() -> RoleTables.defaults(
                        BuiltInRoles.all(), List.of(RoleDefinition.of("super_admin", "Super", List.of())), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the role key super_admin is reserved for network administrators");
    }

    @Test
    void theJsonFormKeepsOrderAndRoundTrips() throws Exception {
        JsonNode json = RoleTables.toJson(BuiltInRoles.all());
        assertThat(JSON.writeValueAsString(json))
                .startsWith("{\"administrator\":{\"name\":\"Administrator\",\"capabilities\":{\"switch_themes\":true,");
        assertThat(RoleTables.fromJson(json)).isEqualTo(BuiltInRoles.all());
        assertThat(List.copyOf(RoleTables.fromJson(json).get(0).capabilities().keySet()))
                .isEqualTo(List.copyOf(BuiltInRoles.all().get(0).capabilities().keySet()));
    }

    @Test
    void aStoredValueThatIsNotATableHasNoRolesAndBadEntriesAreSkipped() throws Exception {
        assertThat(RoleTables.fromJson(TextNode.valueOf("x"))).isEmpty();
        assertThat(RoleTables.fromJson(null)).isEmpty();
        JsonNode stored = JSON.readTree("{\"a\":{\"name\":\"A\",\"capabilities\":{\"read\":1,\"x\":\"0\",\"y\":false}},"
                + "\"b\":\"broken\",\"c\":{\"capabilities\":{}},\"\":{\"name\":\"E\",\"capabilities\":{}}}");
        List<RoleDefinition> roles = RoleTables.fromJson(stored);
        assertThat(roles).extracting(RoleDefinition::key).containsExactly("a", "c");
        assertThat(roles.get(0).capabilities())
                .containsExactly(Map.entry("read", true), Map.entry("x", false), Map.entry("y", false));
        assertThat(roles.get(1).displayName()).isEqualTo("c");
    }

    @Test
    void oddShapesInAStoredTable() throws Exception {
        JsonNode stored = JSON.readTree("{\"a\":{\"name\":7,\"capabilities\":\"read\"},\"b\":null,"
                + "\"d\":{\"name\":\"First\",\"capabilities\":{\"read\":true}},"
                + "\"e\":{\"name\":\"E\",\"capabilities\":[true]},"
                + "\"d\":{\"name\":\"Second\",\"capabilities\":{\"edit\":true}}}");
        List<RoleDefinition> roles = RoleTables.fromJson(stored);
        // Jackson keeps the last value of a duplicate key, at the position of its first occurrence.
        assertThat(roles).extracting(RoleDefinition::key).containsExactly("a", "d", "e");
        assertThat(roles.get(0).displayName()).isEqualTo("a");
        assertThat(roles.get(0).capabilities()).isEmpty();
        assertThat(roles.get(1).displayName()).isEqualTo("Second");
        assertThat(roles.get(1).capabilities()).containsExactly(Map.entry("edit", true));
        assertThat(roles.get(2).capabilities()).isEmpty();
    }
}
