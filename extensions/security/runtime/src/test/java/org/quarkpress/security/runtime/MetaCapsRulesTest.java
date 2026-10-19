package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.CapObject;
import org.quarkpress.security.MissingObject;
import org.quarkpress.security.TaxonomyCapabilities;
import org.quarkpress.security.TermFacts;
import org.quarkpress.security.UserTarget;

class MetaCapsRulesTest {

    private static final MetaCaps CAPS = new MetaCaps(List.of(), List.of(new BuiltInMetaCapabilityNames()));

    private static Set<String> map(String cap, CapObject object, MapContext context) {
        return CAPS.map(cap, Optional.ofNullable(object), context);
    }

    private static UserTarget user(long id) {
        return new UserTarget(UserId.of(id));
    }

    @Test
    void editingUsers() {
        assertThat(map("edit_user", user(7), FakeContext.single(7))).isEmpty();
        assertThat(map("edit_user", user(7), FakeContext.network(7))).isEmpty();
        assertThat(map("edit_users", null, FakeContext.single(7))).containsExactly("edit_users");
        assertThat(map("edit_user", user(5), FakeContext.single(7))).containsExactly("edit_users");
        assertThat(map("edit_user", user(5), FakeContext.single(0))).containsExactly("do_not_allow");
        assertThat(map("edit_users", null, FakeContext.single(0))).containsExactly("do_not_allow");
        assertThat(map("edit_user", user(5), FakeContext.network(7))).containsExactly("do_not_allow");
        assertThat(map("edit_user", user(5), FakeContext.network(7).holding("manage_network_users")))
                .containsExactly("edit_users");
        FakeContext editsSuperAdmin = FakeContext.network(7).holding("manage_network_users");
        editsSuperAdmin.superAdmins.add(9L);
        assertThat(map("edit_user", user(9), editsSuperAdmin)).containsExactly("do_not_allow");
        assertThat(map("edit_users", null, editsSuperAdmin)).containsExactly("edit_users");
        FakeContext superAdmin = FakeContext.network(7).superAdmin();
        superAdmin.superAdmins.add(9L);
        assertThat(map("edit_user", user(9), superAdmin)).containsExactly("edit_users");
    }

    @Test
    void promotingAddingRemovingDeletingAndCreatingUsers() {
        assertThat(map("promote_user", user(5), FakeContext.network(7))).containsExactly("promote_users");
        assertThat(map("add_users", null, FakeContext.network(7))).containsExactly("promote_users");
        assertThat(map("remove_user", user(5), FakeContext.network(7))).containsExactly("remove_users");
        assertThat(map("remove_user", user(7), FakeContext.network(7))).containsExactly("do_not_allow");
        assertThat(map("remove_user", user(7), FakeContext.network(7).superAdmin()))
                .containsExactly("remove_users");
        assertThat(map("remove_user", user(7), FakeContext.single(7))).containsExactly("do_not_allow");
        assertThat(map("remove_user", user(7), FakeContext.single(7).superAdmin()))
                .containsExactly("remove_users");
        assertThat(map("delete_user", user(5), FakeContext.single(7))).containsExactly("delete_users");
        assertThat(map("delete_users", null, FakeContext.network(7))).containsExactly("do_not_allow");
        assertThat(map("delete_users", null, FakeContext.network(7).superAdmin()))
                .containsExactly("delete_users");
        assertThat(map("create_users", null, FakeContext.single(7))).containsExactly("create_users");
        assertThat(map("create_users", null, FakeContext.network(7))).containsExactly("do_not_allow");
        assertThat(map(
                        "create_users",
                        null,
                        FakeContext.network(7).networkOption("add_new_users", TextNode.valueOf("1"))))
                .containsExactly("create_users");
        assertThat(map(
                        "create_users",
                        null,
                        FakeContext.network(7).networkOption("add_new_users", TextNode.valueOf("0"))))
                .containsExactly("do_not_allow");
        assertThat(map("create_users", null, FakeContext.network(7).superAdmin()))
                .containsExactly("create_users");
        assertThat(map("create_app_password", user(7), FakeContext.single(7))).isEmpty();
        assertThat(map("delete_app_password", user(5), FakeContext.single(7))).containsExactly("edit_users");
        assertThat(map("list_app_passwords", user(5), FakeContext.network(7))).containsExactly("do_not_allow");
    }

    @Test
    void terms() {
        TermFacts tag = TermFacts.of(TermId.of(3), TaxonomyCapabilities.POST_TAG);
        TermFacts category = TermFacts.of(TermId.of(4), TaxonomyCapabilities.CATEGORY);
        TermFacts genre = TermFacts.of(TermId.of(5), TaxonomyCapabilities.defaults("genre"));
        FakeContext author = FakeContext.single(7);
        assertThat(map("edit_term", tag, author)).containsExactly("manage_categories");
        assertThat(map("assign_term", tag, author)).containsExactly("edit_posts");
        assertThat(map("delete_term", category, author)).containsExactly("manage_categories");
        assertThat(map("assign_term", category, author)).containsExactly("edit_posts");
        assertThat(map("edit_term", genre, author)).containsExactly("manage_categories");
        assertThat(map("assign_term", genre, author)).containsExactly("edit_posts");
        FakeContext withDefault =
                FakeContext.network(7).superAdmin().siteOption("default_category", IntNode.valueOf(4));
        assertThat(map("delete_term", category, withDefault)).containsExactly("do_not_allow");
        assertThat(map("edit_term", category, withDefault)).containsExactly("manage_categories");
        FakeContext withDefaultTerm = FakeContext.single(7).siteOption("default_term_genre", TextNode.valueOf("5"));
        assertThat(map("delete_term", genre, withDefaultTerm)).containsExactly("do_not_allow");
        assertThat(map("edit_term", null, author)).containsExactly("do_not_allow");
        assertThat(map("edit_term", new MissingObject(9), author)).containsExactly("do_not_allow");
        assertThat(map("edit_term", new TermFacts(TermId.of(6), Optional.empty()), author))
                .containsExactly("do_not_allow");
        assertThat(map("manage_post_tags", null, author)).containsExactly("manage_categories");
        assertThat(map("assign_categories", null, author)).containsExactly("edit_posts");
        TermFacts custom =
                TermFacts.of(TermId.of(7), TaxonomyCapabilities.of("shelf", Map.of("edit_terms", "manage_shelves")));
        assertThat(map("edit_term", custom, author)).containsExactly("manage_shelves");
    }

    @Test
    void capabilitiesANetworkRestrictsToItsAdministrators() {
        List<String> sameName = List.of(
                "edit_files",
                "edit_plugins",
                "edit_themes",
                "update_plugins",
                "delete_plugins",
                "install_plugins",
                "update_themes",
                "delete_themes",
                "install_themes",
                "update_core");
        for (String cap : sameName) {
            assertThat(map(cap, null, FakeContext.single(7))).as(cap).containsExactly(cap);
            assertThat(map(cap, null, FakeContext.network(7))).as(cap).containsExactly("do_not_allow");
            assertThat(map(cap, null, FakeContext.network(7).superAdmin()))
                    .as(cap)
                    .containsExactly(cap);
        }
        assertThat(map("upload_plugins", null, FakeContext.single(7))).containsExactly("install_plugins");
        assertThat(map("upload_themes", null, FakeContext.network(7).superAdmin()))
                .containsExactly("install_themes");
        assertThat(map("upload_themes", null, FakeContext.network(7))).containsExactly("do_not_allow");
        assertThat(map("update_languages", null, FakeContext.single(7))).containsExactly("install_languages");
        assertThat(map("install_languages", null, FakeContext.network(7))).containsExactly("do_not_allow");
        assertThat(map("update_php", null, FakeContext.single(7))).containsExactly("update_core");
        assertThat(map("update_https", null, FakeContext.single(7))).containsExactly("manage_options", "update_core");
        assertThat(map("update_https", null, FakeContext.network(7))).containsExactly("do_not_allow");
        for (String cap : List.of("unfiltered_html", "edit_css")) {
            assertThat(map(cap, null, FakeContext.single(7))).as(cap).containsExactly("unfiltered_html");
            assertThat(map(cap, null, FakeContext.network(7))).as(cap).containsExactly("do_not_allow");
            assertThat(map(cap, null, FakeContext.network(7).superAdmin()))
                    .as(cap)
                    .containsExactly("unfiltered_html");
            FakeContext disallowed = FakeContext.network(7).superAdmin();
            disallowed.disallowUnfilteredHtml = true;
            assertThat(map(cap, null, disallowed)).as(cap).containsExactly("do_not_allow");
        }
        assertThat(map("unfiltered_upload", null, FakeContext.single(7).superAdmin()))
                .containsExactly("do_not_allow");
        assertThat(map("activate_plugins", null, FakeContext.single(7))).containsExactly("activate_plugins");
        assertThat(map("deactivate_plugin", null, FakeContext.network(7)))
                .containsExactly("activate_plugins", "manage_network_plugins");
        assertThat(map(
                        "activate_plugin",
                        null,
                        FakeContext.network(7)
                                .networkOption(
                                        "menu_items",
                                        JsonNodeFactory.instance.objectNode().put("plugins", "1"))))
                .containsExactly("activate_plugins");
        assertThat(map("delete_site", null, FakeContext.single(7).superAdmin())).containsExactly("do_not_allow");
        assertThat(map("delete_site", null, FakeContext.network(7))).containsExactly("manage_options");
        assertThat(map("setup_network", null, FakeContext.single(7))).containsExactly("manage_options");
        assertThat(map("setup_network", null, FakeContext.network(7))).containsExactly("manage_network_options");
        assertThat(map("manage_privacy_options", null, FakeContext.single(7))).containsExactly("manage_options");
        assertThat(map("erase_others_personal_data", null, FakeContext.network(7)))
                .containsExactly("manage_network");
    }

    @Test
    void theRemainingNamedCapabilities() {
        assertThat(map("manage_links", null, FakeContext.single(7))).containsExactly("do_not_allow");
        assertThat(map(
                        "manage_links",
                        null,
                        FakeContext.single(7).siteOption("link_manager_enabled", IntNode.valueOf(1))))
                .containsExactly("manage_links");
        assertThat(map(
                        "manage_links",
                        null,
                        FakeContext.single(7).siteOption("link_manager_enabled", TextNode.valueOf("0"))))
                .containsExactly("do_not_allow");
        assertThat(map("customize", null, FakeContext.single(7))).containsExactly("edit_theme_options");
        assertThat(map("resume_plugin", null, FakeContext.network(7))).containsExactly("resume_plugins");
        assertThat(map("resume_theme", null, FakeContext.network(7))).containsExactly("resume_themes");
        for (String cap : List.of(
                "create_sites",
                "delete_sites",
                "manage_network",
                "manage_sites",
                "manage_network_users",
                "manage_network_plugins",
                "manage_network_themes",
                "manage_network_options",
                "upgrade_network")) {
            assertThat(map(cap, null, FakeContext.single(7))).as(cap).containsExactly(cap);
        }
        assertThat(map("manage_site_options", null, FakeContext.network(7))).containsExactly("manage_site_options");
        assertThat(map("manage_options", null, FakeContext.network(7))).containsExactly("manage_options");
        assertThat(map("exist", null, FakeContext.single(0))).containsExactly("exist");
        assertThat(map("editor", null, FakeContext.single(7))).containsExactly("editor");
    }

    @Test
    void namesWaitingForTheirModulesBehaveAsUnknownNames() {
        for (String cap :
                List.of("edit_post_meta", "add_term_meta", "delete_user_meta", "edit_comment", "edit_block_binding")) {
            assertThat(map(cap, user(5), FakeContext.single(7))).as(cap).containsExactly(cap);
        }
    }
}
