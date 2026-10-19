package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.RoleDefinition;

class BuiltInRolesTest {

    private static RoleDefinition role(String key) {
        return BuiltInRoles.all().stream()
                .filter(r -> r.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void fiveRolesInWordPressOrderWithEnglishNames() {
        assertThat(BuiltInRoles.all())
                .extracting(RoleDefinition::key)
                .containsExactly("administrator", "editor", "author", "contributor", "subscriber");
        assertThat(BuiltInRoles.all())
                .extracting(RoleDefinition::displayName)
                .containsExactly("Administrator", "Editor", "Author", "Contributor", "Subscriber");
    }

    @Test
    void eachRoleHoldsWordPressCapabilitiesInItsOrder() {
        assertThat(role("administrator").capabilities()).hasSize(61).doesNotContainValue(false);
        assertThat(role("editor").capabilities()).hasSize(34);
        assertThat(role("author").capabilities().keySet())
                .containsExactly(
                        "upload_files",
                        "edit_posts",
                        "edit_published_posts",
                        "publish_posts",
                        "read",
                        "level_2",
                        "level_1",
                        "level_0",
                        "delete_posts",
                        "delete_published_posts");
        assertThat(role("contributor").capabilities().keySet())
                .containsExactly("edit_posts", "read", "level_1", "level_0", "delete_posts");
        assertThat(role("subscriber").capabilities())
                .containsExactly(Map.entry("read", true), Map.entry("level_0", true));
        List<String> admin = List.copyOf(role("administrator").capabilities().keySet());
        assertThat(admin.subList(0, 3)).containsExactly("switch_themes", "edit_themes", "activate_plugins");
        assertThat(admin.subList(58, 61)).containsExactly("edit_theme_options", "delete_themes", "export");
    }

    @Test
    void metaAndNetworkCapabilitiesAreInNoRole() {
        for (RoleDefinition role : BuiltInRoles.all()) {
            assertThat(role.capabilities())
                    .doesNotContainKeys(
                            "customize",
                            "delete_site",
                            "add_users",
                            "super_admin",
                            "manage_network",
                            "create_sites",
                            "setup_network",
                            "manage_privacy_options");
        }
        assertThat(role("administrator").capabilities())
                .containsKeys("unfiltered_html", "create_users", "delete_users", "edit_users", "update_core");
        assertThat(role("editor").capabilities())
                .containsKeys("unfiltered_html", "manage_links")
                .doesNotContainKeys("manage_options", "edit_theme_options", "list_users", "export", "import");
    }
}
