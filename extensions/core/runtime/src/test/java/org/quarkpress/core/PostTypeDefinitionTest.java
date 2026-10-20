package org.quarkpress.core;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostTypeDefinitionTest {

    @Test
    void theNameIsSanitizedAsAKey() {
        assertThat(PostTypeDefinition.builder("Book").build().key()).isEqualTo("book");
    }

    @Test
    void aNameOfMoreThanTwentyCharactersIsRefused() {
        assertThatThrownBy(() ->
                        PostTypeDefinition.builder("a_very_long_post_type_name").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "post type 'a_very_long_post_type_name': Post type names must be between 1 and 20 characters in length.");
        assertThatThrownBy(() -> PostTypeDefinition.builder("a".repeat(21)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("post type '" + "a".repeat(21)
                        + "': Post type names must be between 1 and 20 characters in length.");
        assertThatThrownBy(() -> PostTypeDefinition.builder("!!!").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("post type '!!!': Post type names must be between 1 and 20 characters in length.");
        assertThat(PostTypeDefinition.builder("a".repeat(20)).build().key()).isEqualTo("a".repeat(20));
    }

    @Test
    void aPublicTypeDerivesItsFlagsFromPublic() {
        PostTypeDefinition book =
                PostTypeDefinition.builder("book").publicly(true).build();
        assertThat(book.publiclyQueryable()).isTrue();
        assertThat(book.showUi()).isTrue();
        assertThat(book.showInMenu()).isTrue();
        assertThat(book.showInNavMenus()).isTrue();
        assertThat(book.showInAdminBar()).isTrue();
        assertThat(book.embeddable()).isTrue();
        assertThat(book.excludeFromSearch()).isFalse();
        assertThat(book.mapMetaCap()).isTrue();
        assertThat(book.capabilityType()).isEqualTo("post");
        assertThat(book.capabilityTypePlural()).isEqualTo("posts");
        assertThat(book.supports()).containsExactly("title", "editor", "autosave");
        assertThat(book.restBase()).isEmpty();
        assertThat(book.builtIn()).isFalse();
    }

    @Test
    void aTypeThatIsNotPublicIsHiddenEverywhere() {
        PostTypeDefinition hidden = PostTypeDefinition.builder("hidden").build();
        assertThat(hidden.publicly()).isFalse();
        assertThat(hidden.showUi()).isFalse();
        assertThat(hidden.showInMenu()).isFalse();
        assertThat(hidden.showInAdminBar()).isFalse();
        assertThat(hidden.excludeFromSearch()).isTrue();
        assertThat(hidden.publiclyQueryable()).isFalse();
        assertThat(hidden.embeddable()).isFalse();
        assertThat(hidden.showInNavMenus()).isFalse();
    }

    @Test
    void anExplicitFlagOverridesTheOneDerivedFromPublic() {
        PostTypeDefinition open = PostTypeDefinition.builder("open")
                .publiclyQueryable(false)
                .embeddable(false)
                .showInNavMenus(false)
                .excludeFromSearch(true)
                .showInAdminBar(false)
                .showInMenu(true)
                .publicly(true)
                .build();
        assertThat(open.publiclyQueryable()).isFalse();
        assertThat(open.embeddable()).isFalse();
        assertThat(open.showInNavMenus()).isFalse();
        assertThat(open.excludeFromSearch()).isTrue();
        assertThat(open.showInAdminBar()).isFalse();
        assertThat(open.showInMenu()).isTrue();
        assertThat(open.showUi()).isTrue();
        assertThat(PostTypeDefinition.builder("quiet")
                        .publicly(true)
                        .showInMenu(false)
                        .build()
                        .showInMenu())
                .isFalse();
        PostTypeDefinition closed = PostTypeDefinition.builder("closed")
                .publiclyQueryable(true)
                .embeddable(true)
                .showInNavMenus(true)
                .excludeFromSearch(false)
                .showUi(true)
                .showInMenu(false)
                .showInAdminBar(true)
                .build();
        assertThat(closed.publicly()).isFalse();
        assertThat(closed.publiclyQueryable()).isTrue();
        assertThat(closed.embeddable()).isTrue();
        assertThat(closed.showInNavMenus()).isTrue();
        assertThat(closed.excludeFromSearch()).isFalse();
        assertThat(closed.showInMenu()).isFalse();
        assertThat(closed.showInAdminBar()).isTrue();
    }

    @Test
    void aTypeWithoutUiNeverShowsInAMenu() {
        PostTypeDefinition type = PostTypeDefinition.builder("tool")
                .showUi(false)
                .showInMenu(true)
                .build();
        assertThat(type.showInMenu()).isFalse();
        assertThat(type.showInAdminBar()).isFalse();
    }

    @Test
    void aCapabilityTypePairDoesNotMapMetaCapabilitiesAndKeepsItsSingular() {
        PostTypeDefinition ledger = PostTypeDefinition.builder("ledger")
                .capabilityType("entry", "entries")
                .build();
        assertThat(ledger.mapMetaCap()).isFalse();
        assertThat(ledger.capabilityType()).isEqualTo("entry");
        assertThat(ledger.capabilityTypePlural()).isEqualTo("entries");
    }

    @Test
    void givenCapabilitiesTurnOffTheDefaultMapping() {
        PostTypeDefinition type = PostTypeDefinition.builder("doc")
                .capabilities(Map.of("create_posts", "upload_files"))
                .build();
        assertThat(type.mapMetaCap()).isFalse();
        assertThat(PostTypeDefinition.builder("page2")
                        .capabilityType("page")
                        .build()
                        .mapMetaCap())
                .isTrue();
    }

    @Test
    void anUnknownCapabilityTypeDoesNotMapAndAnExplicitFlagWins() {
        assertThat(PostTypeDefinition.builder("novel")
                        .capabilityType("book")
                        .build()
                        .mapMetaCap())
                .isFalse();
        assertThat(PostTypeDefinition.builder("doc2")
                        .capabilities(Map.of("create_posts", "upload_files"))
                        .mapMetaCap(true)
                        .build()
                        .mapMetaCap())
                .isTrue();
        assertThat(PostTypeDefinition.builder("p3").mapMetaCap(false).build().mapMetaCap())
                .isFalse();
    }

    @Test
    void supportsFollowWordPressDefaulting() {
        assertThat(PostTypeDefinition.builder("a")
                        .supports(List.of("title"))
                        .build()
                        .supports())
                .containsExactly("title");
        assertThat(PostTypeDefinition.builder("b").supports(List.of()).build().supports())
                .containsExactly("title", "editor", "autosave");
        assertThat(PostTypeDefinition.builder("c").noSupports().build().supports())
                .isEmpty();
        assertThat(PostTypeDefinition.builder("d")
                        .supports(List.of("editor", "title", "editor"))
                        .build()
                        .supports())
                .containsExactly("editor", "title", "autosave");
        assertThat(PostTypeDefinition.builder("e").build().supports("editor")).isTrue();
    }

    @Test
    void labelsOfATypeWithoutLabelsAreThePostDefaults() {
        Map<String, String> labels = PostTypeDefinition.builder("book").build().labels();
        assertThat(labels)
                .containsEntry("name", "Posts")
                .containsEntry("singular_name", "Post")
                .containsEntry("add_new", "Add")
                .containsEntry("add_new_item", "Add Post")
                .containsEntry("menu_name", "Posts")
                .containsEntry("all_items", "All Posts")
                .containsEntry("archives", "Post Archives")
                .containsEntry("name_admin_bar", "book")
                .doesNotContainKey("parent_item_colon")
                .doesNotContainKey("template_name");
        assertThat(labels.keySet()).startsWith("name", "singular_name", "add_new", "add_new_item");
    }

    @Test
    void everyDefaultLabelOfAFlatTypeIsWordPressesEnglish() {
        Map<String, String> labels = PostTypeDefinition.builder("book").build().labels();
        assertThat(labels)
                .containsExactly(
                        entry("name", "Posts"),
                        entry("singular_name", "Post"),
                        entry("add_new", "Add"),
                        entry("add_new_item", "Add Post"),
                        entry("edit_item", "Edit Post"),
                        entry("new_item", "New Post"),
                        entry("view_item", "View Post"),
                        entry("view_items", "View Posts"),
                        entry("search_items", "Search Posts"),
                        entry("not_found", "No posts found."),
                        entry("not_found_in_trash", "No posts found in Trash."),
                        entry("all_items", "All Posts"),
                        entry("archives", "Post Archives"),
                        entry("attributes", "Post Attributes"),
                        entry("insert_into_item", "Insert into post"),
                        entry("uploaded_to_this_item", "Uploaded to this post"),
                        entry("featured_image", "Featured image"),
                        entry("set_featured_image", "Set featured image"),
                        entry("remove_featured_image", "Remove featured image"),
                        entry("use_featured_image", "Use as featured image"),
                        entry("filter_items_list", "Filter posts list"),
                        entry("filter_by_date", "Filter by date"),
                        entry("items_list_navigation", "Posts list navigation"),
                        entry("items_list", "Posts list"),
                        entry("item_published", "Post published."),
                        entry("item_published_privately", "Post published privately."),
                        entry("item_reverted_to_draft", "Post reverted to draft."),
                        entry("item_trashed", "Post trashed."),
                        entry("item_scheduled", "Post scheduled."),
                        entry("item_updated", "Post updated."),
                        entry("item_link", "Post Link"),
                        entry("item_link_description", "A link to a post."),
                        entry("menu_name", "Posts"),
                        entry("name_admin_bar", "book"));
    }

    @Test
    void everyDefaultLabelOfAHierarchicalTypeIsWordPressesEnglish() {
        Map<String, String> labels =
                PostTypeDefinition.builder("section").hierarchical(true).build().labels();
        assertThat(labels)
                .containsExactly(
                        entry("name", "Pages"),
                        entry("singular_name", "Page"),
                        entry("add_new", "Add"),
                        entry("add_new_item", "Add Page"),
                        entry("edit_item", "Edit Page"),
                        entry("new_item", "New Page"),
                        entry("view_item", "View Page"),
                        entry("view_items", "View Pages"),
                        entry("search_items", "Search Pages"),
                        entry("not_found", "No pages found."),
                        entry("not_found_in_trash", "No pages found in Trash."),
                        entry("parent_item_colon", "Parent Page:"),
                        entry("all_items", "All Pages"),
                        entry("archives", "Page Archives"),
                        entry("attributes", "Page Attributes"),
                        entry("insert_into_item", "Insert into page"),
                        entry("uploaded_to_this_item", "Uploaded to this page"),
                        entry("featured_image", "Featured image"),
                        entry("set_featured_image", "Set featured image"),
                        entry("remove_featured_image", "Remove featured image"),
                        entry("use_featured_image", "Use as featured image"),
                        entry("filter_items_list", "Filter pages list"),
                        entry("filter_by_date", "Filter by date"),
                        entry("items_list_navigation", "Pages list navigation"),
                        entry("items_list", "Pages list"),
                        entry("item_published", "Page published."),
                        entry("item_published_privately", "Page published privately."),
                        entry("item_reverted_to_draft", "Page reverted to draft."),
                        entry("item_trashed", "Page trashed."),
                        entry("item_scheduled", "Page scheduled."),
                        entry("item_updated", "Page updated."),
                        entry("item_link", "Page Link"),
                        entry("item_link_description", "A link to a page."),
                        entry("menu_name", "Pages"),
                        entry("name_admin_bar", "section"));
    }

    @Test
    void givenNamesReplaceTheDerivedLabelsInWordPressesOrder() {
        Map<String, String> labels = PostTypeDefinition.builder("chapter")
                .hierarchical(true)
                .labels(Map.of("name", "Chapters", "singular_name", "Chapter"))
                .build()
                .labels();
        assertThat(labels)
                .containsExactly(
                        entry("name", "Chapters"),
                        entry("singular_name", "Chapter"),
                        entry("add_new", "Add"),
                        entry("add_new_item", "Add Page"),
                        entry("edit_item", "Edit Page"),
                        entry("new_item", "New Page"),
                        entry("view_item", "View Page"),
                        entry("view_items", "View Pages"),
                        entry("search_items", "Search Pages"),
                        entry("not_found", "No pages found."),
                        entry("not_found_in_trash", "No pages found in Trash."),
                        entry("parent_item_colon", "Parent Page:"),
                        entry("all_items", "Chapters"),
                        entry("archives", "Chapters"),
                        entry("attributes", "Page Attributes"),
                        entry("insert_into_item", "Insert into page"),
                        entry("uploaded_to_this_item", "Uploaded to this page"),
                        entry("featured_image", "Featured image"),
                        entry("set_featured_image", "Set featured image"),
                        entry("remove_featured_image", "Remove featured image"),
                        entry("use_featured_image", "Use as featured image"),
                        entry("filter_items_list", "Filter pages list"),
                        entry("filter_by_date", "Filter by date"),
                        entry("items_list_navigation", "Pages list navigation"),
                        entry("items_list", "Pages list"),
                        entry("item_published", "Page published."),
                        entry("item_published_privately", "Page published privately."),
                        entry("item_reverted_to_draft", "Page reverted to draft."),
                        entry("item_trashed", "Page trashed."),
                        entry("item_scheduled", "Page scheduled."),
                        entry("item_updated", "Page updated."),
                        entry("item_link", "Page Link"),
                        entry("item_link_description", "A link to a page."),
                        entry("menu_name", "Chapters"),
                        entry("name_admin_bar", "Chapter"),
                        entry("template_name", "Single item: Chapter"));
    }

    @Test
    void givenNamesFlowIntoTheDerivedLabels() {
        Map<String, String> labels = PostTypeDefinition.builder("chapter")
                .hierarchical(true)
                .labels(Map.of("name", "Chapters", "singular_name", "Chapter"))
                .build()
                .labels();
        assertThat(labels)
                .containsEntry("name", "Chapters")
                .containsEntry("singular_name", "Chapter")
                .containsEntry("name_admin_bar", "Chapter")
                .containsEntry("menu_name", "Chapters")
                .containsEntry("all_items", "Chapters")
                .containsEntry("archives", "Chapters")
                .containsEntry("add_new_item", "Add Page")
                .containsEntry("parent_item_colon", "Parent Page:")
                .containsEntry("template_name", "Single item: Chapter");
    }

    @Test
    void theLabelArgumentNamesTheTypeWhenNoNameLabelIsGiven() {
        Map<String, String> labels =
                PostTypeDefinition.builder("movie").label("Movies").build().labels();
        assertThat(labels)
                .containsEntry("name", "Movies")
                .containsEntry("singular_name", "Movies")
                .containsEntry("template_name", "Single item: Movies");
    }
}
