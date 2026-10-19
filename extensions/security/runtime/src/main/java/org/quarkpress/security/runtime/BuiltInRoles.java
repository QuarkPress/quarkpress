package org.quarkpress.security.runtime;

import java.util.List;
import org.quarkpress.core.RoleDefinition;

/**
 * WordPress's five default roles, with the capabilities and the order its installer gives them. The table is the same
 * for a single site and a network; the network's restrictions apply when a capability is checked.
 */
public final class BuiltInRoles {

    public static final String ADMINISTRATOR = "administrator";
    public static final String EDITOR = "editor";
    public static final String AUTHOR = "author";
    public static final String CONTRIBUTOR = "contributor";
    public static final String SUBSCRIBER = "subscriber";

    private static final List<RoleDefinition> ALL = List.of(
            RoleDefinition.of(
                    ADMINISTRATOR,
                    "Administrator",
                    List.of(
                            "switch_themes",
                            "edit_themes",
                            "activate_plugins",
                            "edit_plugins",
                            "edit_users",
                            "edit_files",
                            "manage_options",
                            "moderate_comments",
                            "manage_categories",
                            "manage_links",
                            "upload_files",
                            "import",
                            "unfiltered_html",
                            "edit_posts",
                            "edit_others_posts",
                            "edit_published_posts",
                            "publish_posts",
                            "edit_pages",
                            "read",
                            "level_10",
                            "level_9",
                            "level_8",
                            "level_7",
                            "level_6",
                            "level_5",
                            "level_4",
                            "level_3",
                            "level_2",
                            "level_1",
                            "level_0",
                            "edit_others_pages",
                            "edit_published_pages",
                            "publish_pages",
                            "delete_pages",
                            "delete_others_pages",
                            "delete_published_pages",
                            "delete_posts",
                            "delete_others_posts",
                            "delete_published_posts",
                            "delete_private_posts",
                            "edit_private_posts",
                            "read_private_posts",
                            "delete_private_pages",
                            "edit_private_pages",
                            "read_private_pages",
                            "delete_users",
                            "create_users",
                            "unfiltered_upload",
                            "edit_dashboard",
                            "update_plugins",
                            "delete_plugins",
                            "install_plugins",
                            "update_themes",
                            "install_themes",
                            "update_core",
                            "list_users",
                            "remove_users",
                            "promote_users",
                            "edit_theme_options",
                            "delete_themes",
                            "export")),
            RoleDefinition.of(
                    EDITOR,
                    "Editor",
                    List.of(
                            "moderate_comments",
                            "manage_categories",
                            "manage_links",
                            "upload_files",
                            "unfiltered_html",
                            "edit_posts",
                            "edit_others_posts",
                            "edit_published_posts",
                            "publish_posts",
                            "edit_pages",
                            "read",
                            "level_7",
                            "level_6",
                            "level_5",
                            "level_4",
                            "level_3",
                            "level_2",
                            "level_1",
                            "level_0",
                            "edit_others_pages",
                            "edit_published_pages",
                            "publish_pages",
                            "delete_pages",
                            "delete_others_pages",
                            "delete_published_pages",
                            "delete_posts",
                            "delete_others_posts",
                            "delete_published_posts",
                            "delete_private_posts",
                            "edit_private_posts",
                            "read_private_posts",
                            "delete_private_pages",
                            "edit_private_pages",
                            "read_private_pages")),
            RoleDefinition.of(
                    AUTHOR,
                    "Author",
                    List.of(
                            "upload_files",
                            "edit_posts",
                            "edit_published_posts",
                            "publish_posts",
                            "read",
                            "level_2",
                            "level_1",
                            "level_0",
                            "delete_posts",
                            "delete_published_posts")),
            RoleDefinition.of(
                    CONTRIBUTOR, "Contributor", List.of("edit_posts", "read", "level_1", "level_0", "delete_posts")),
            RoleDefinition.of(SUBSCRIBER, "Subscriber", List.of("read", "level_0")));

    private BuiltInRoles() {}

    public static List<RoleDefinition> all() {
        return ALL;
    }
}
