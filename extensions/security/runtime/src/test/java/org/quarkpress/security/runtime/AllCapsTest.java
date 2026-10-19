package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.RoleDefinition;

class AllCapsTest {

    private static final Map<String, RoleDefinition> SITE = RoleTables.byKey(BuiltInRoles.all());

    private static Map<String, Boolean> caps(Object... pairs) {
        Map<String, Boolean> caps = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            caps.put((String) pairs[i], (Boolean) pairs[i + 1]);
        }
        return caps;
    }

    @Test
    void anEditorHoldsTheEditorCapabilitiesAndTheRoleKey() {
        Map<String, Boolean> all = AllCaps.of(SITE, List.of("editor"), Map.of());
        assertThat(AllCaps.holds(all, "edit_others_posts")).isTrue();
        assertThat(AllCaps.holds(all, "editor")).isTrue();
        assertThat(AllCaps.holds(all, "manage_options")).isFalse();
    }

    @Test
    void aUserDenialBeatsTheRole() {
        Map<String, Boolean> all = AllCaps.of(SITE, List.of("editor"), caps("edit_others_posts", false));
        assertThat(AllCaps.holds(all, "edit_others_posts")).isFalse();
        assertThat(AllCaps.holds(all, "edit_posts")).isTrue();
    }

    @Test
    void aUserGrantAddsToTheRole() {
        Map<String, Boolean> all = AllCaps.of(SITE, List.of("subscriber"), caps("edit_posts", true));
        assertThat(AllCaps.holds(all, "edit_posts")).isTrue();
    }

    @Test
    void theLaterRoleWins() {
        Map<String, RoleDefinition> site = new LinkedHashMap<>(SITE);
        site.put("contributor", site.get("contributor").withCapability("publish_posts", false));
        assertThat(AllCaps.holds(AllCaps.of(site, List.of("author", "contributor"), Map.of()), "publish_posts"))
                .isFalse();
        assertThat(AllCaps.holds(AllCaps.of(site, List.of("contributor", "author"), Map.of()), "publish_posts"))
                .isTrue();
    }

    @Test
    void aDeniedRoleKeyStillGrantsTheRoleButNotTheKey() {
        Map<String, Boolean> all = AllCaps.of(SITE, List.of(), caps("editor", false));
        assertThat(AllCaps.roles(SITE, List.of(), caps("editor", false))).containsExactly("editor");
        assertThat(AllCaps.holds(all, "edit_others_posts")).isTrue();
        assertThat(AllCaps.holds(all, "editor")).isFalse();
    }

    @Test
    void aRemovedRoleIsAPlainCapability() {
        Map<String, Boolean> all = AllCaps.of(SITE, List.of("shop_manager"), Map.of());
        assertThat(AllCaps.roles(SITE, List.of("shop_manager"), Map.of())).isEmpty();
        assertThat(AllCaps.holds(all, "shop_manager")).isTrue();
        assertThat(AllCaps.holds(all, "read")).isFalse();
    }

    @Test
    void roleKeysAreExact() {
        assertThat(AllCaps.roles(SITE, List.of("Editor"), Map.of())).isEmpty();
        assertThat(AllCaps.holds(AllCaps.of(SITE, List.of("Editor"), Map.of()), "edit_posts"))
                .isFalse();
    }

    @Test
    void rolesAreListedOnceInOrder() {
        assertThat(AllCaps.roles(SITE, List.of("author", "editor", "author"), caps("subscriber", true)))
                .containsExactly("author", "editor", "subscriber");
    }

    @Test
    void theResultIsAFreshModifiableMap() {
        Map<String, Boolean> all = AllCaps.of(SITE, List.of("subscriber"), Map.of());
        all.put("x", true);
        assertThat(AllCaps.of(SITE, List.of("subscriber"), Map.of())).doesNotContainKey("x");
        assertThat(new ArrayList<>(all.keySet())).startsWith("read", "level_0");
    }
}
