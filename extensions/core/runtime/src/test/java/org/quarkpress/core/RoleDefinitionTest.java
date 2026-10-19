package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoleDefinitionTest {

    @Test
    void capabilitiesKeepTheirOrderAndCannotBeChanged() {
        Map<String, Boolean> caps = new LinkedHashMap<>();
        caps.put("z", true);
        caps.put("a", false);
        RoleDefinition role = new RoleDefinition("shop", "Shop", caps);
        caps.put("b", true);
        assertThat(role.capabilities().keySet()).containsExactly("z", "a");
        assertThatThrownBy(() -> role.capabilities().put("c", true)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aListGrantsEveryNamedCapability() {
        RoleDefinition role = RoleDefinition.of("shop_manager", "Shop manager", List.of("read", "edit_posts"));
        assertThat(role.capabilities()).containsExactly(Map.entry("read", true), Map.entry("edit_posts", true));
    }

    @Test
    void changesKeepPositionsAndAppendNewCapabilities() {
        RoleDefinition role = RoleDefinition.of("r", "R", List.of("a", "b"));
        assertThat(role.withCapability("a", false).capabilities())
                .containsExactly(Map.entry("a", false), Map.entry("b", true));
        assertThat(role.withCapability("c", true).capabilities().keySet()).containsExactly("a", "b", "c");
        assertThat(role.withoutCapability("a").capabilities().keySet()).containsExactly("b");
    }

    @Test
    void anEmptyKeyAndNullsAreRefused() {
        assertThatThrownBy(() -> RoleDefinition.of("", "Nothing", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a role key must not be empty");
        Map<String, Boolean> withNull = new LinkedHashMap<>();
        withNull.put("read", null);
        assertThatThrownBy(() -> new RoleDefinition("r", "R", withNull))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("a role's capabilities hold no null");
    }
}
