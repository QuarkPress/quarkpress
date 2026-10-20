package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.arc.All;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostType;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.security.MetaCapabilityNames;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.PostVisibility;
import org.quarkpress.security.runtime.MapContext;
import org.quarkpress.security.runtime.MetaCaps;

class RegistriesDbTest {

    @PostType(
            key = "book",
            name = "Books",
            singularName = "Book",
            publicly = true,
            capabilityType = "book",
            mapMetaCap = true,
            supports = {"title", "editor", "author"},
            labels = {"add_new_item=Add Book"})
    static class Book {}

    @PostType(
            key = "note",
            publicly = true,
            excludeFromSearch = true,
            showUi = false,
            hasArchive = true,
            description = "Short notes")
    static class Note {}

    @PostType(key = "gadget", capabilityType = "gadget", mapMetaCap = false)
    static class Gadget {}

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(RegistriesDbTest.class, Book.class, Note.class, Gadget.class);

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Inject
    @All
    List<MetaCapabilityNames> metaNames;

    @Inject
    MetaCaps metaCaps;

    @Test
    void builtInTypesComeFirstThenRegisteredOnes() {
        assertThat(types.all())
                .extracting(PostTypeDefinition::key)
                .containsExactly("post", "page", "attachment", "wp_block", "book", "gadget", "note");
        assertThat(types.find("post").orElseThrow().builtIn()).isTrue();
        assertThat(types.find("book").orElseThrow().builtIn()).isFalse();
    }

    @Test
    void builtInTypesHaveWordPressFlags() {
        assertThat(types.hierarchical("page")).isTrue();
        assertThat(types.hierarchical("wp_block")).isFalse();
        assertThat(types.hierarchical("nope")).isFalse();
        assertThat(types.supports("post", "excerpt")).isTrue();
        assertThat(types.supports("page", "excerpt")).isFalse();
        assertThat(types.supports("wp_block", "excerpt")).isTrue();
        assertThat(types.find("post").orElseThrow().supports())
                .containsExactly(
                        "title",
                        "editor",
                        "author",
                        "thumbnail",
                        "excerpt",
                        "trackbacks",
                        "custom-fields",
                        "comments",
                        "revisions",
                        "post-formats",
                        "autosave");
        assertThat(types.find("attachment").orElseThrow().supports()).containsExactly("title", "author", "comments");
        assertThat(types.find("page").orElseThrow().publiclyQueryable()).isFalse();
        assertThat(types.find("wp_block").orElseThrow().publicly()).isFalse();
        assertThat(types.find("wp_block").orElseThrow().showUi()).isTrue();
        assertThat(types.find("wp_block").orElseThrow().showInMenu()).isFalse();
        assertThat(types.find("post").orElseThrow().restBase()).contains("posts");
        assertThat(types.find("attachment").orElseThrow().restBase()).contains("media");
    }

    @Test
    void builtInTypesHaveWordPressLabels() {
        assertThat(types.find("post").orElseThrow().labels())
                .containsEntry("name", "Posts")
                .containsEntry("name_admin_bar", "Post")
                .doesNotContainKey("template_name");
        assertThat(types.find("attachment").orElseThrow().labels())
                .containsEntry("name", "Media")
                .containsEntry("singular_name", "Media")
                .containsEntry("view_item", "View Media File")
                .containsEntry("template_name", "Single item: Media");
        assertThat(types.find("wp_block").orElseThrow().labels())
                .containsEntry("name", "Patterns")
                .containsEntry("singular_name", "Pattern")
                .containsEntry("edit_item", "Edit Block Pattern");
    }

    @Test
    void anyCoversTheTypesNotExcludedFromSearch() {
        assertThat(types.searchable()).containsExactly("post", "page", "attachment", "book");
    }

    @Test
    void capabilityNamesFollowTheCapabilityType() {
        assertThat(types.capabilities("post")).contains(PostTypeCapabilities.POST);
        assertThat(types.capabilities("page")).contains(PostTypeCapabilities.PAGE);
        assertThat(types.capabilities("attachment")).contains(PostTypeCapabilities.ATTACHMENT);
        assertThat(types.capabilities("wp_block")).contains(PostTypeCapabilities.BLOCK);
        assertThat(types.capabilities("nope")).isEmpty();
        assertThat(types.capabilitiesOrPost("nope")).isEqualTo(PostTypeCapabilities.POST);
        PostTypeCapabilities book = types.capabilities("book").orElseThrow();
        assertThat(book.editPost()).isEqualTo("edit_book");
        assertThat(book.editPosts()).isEqualTo("edit_books");
        assertThat(book.publishPosts()).isEqualTo("publish_books");
    }

    @Test
    void aRegisteredTypeMapsItsMetaCapabilityNames() {
        Map<String, String> names = new java.util.LinkedHashMap<>();
        metaNames.forEach(bean -> names.putAll(bean.metaCapabilities()));
        assertThat(names)
                .containsEntry("edit_book", "edit_post")
                .containsEntry("read_book", "read_post")
                .containsEntry("delete_book", "delete_post");
        assertThat(names.keySet()).noneMatch(name -> name.endsWith("_gadget"));
    }

    @Test
    void securityMapsARegisteredTypesMetaCapabilitiesAsTheCoreOnes() {
        MapContext context = new LoggedOut();
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(metaCaps.map("edit_book", Optional.empty(), context))
                .isEqualTo(metaCaps.map("edit_post", Optional.empty(), context));
        soft.assertThat(metaCaps.map("delete_book", Optional.empty(), context))
                .isEqualTo(metaCaps.map("delete_post", Optional.empty(), context));
        soft.assertThat(metaCaps.map("edit_gadget", Optional.empty(), context)).containsExactly("edit_gadget");
        soft.assertAll();
    }

    @Test
    void builtInTypesHaveWordPressFeaturesAndRestRoutes() {
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(types.find("page").orElseThrow().supports())
                .containsExactly(
                        "title",
                        "editor",
                        "author",
                        "thumbnail",
                        "page-attributes",
                        "custom-fields",
                        "comments",
                        "revisions",
                        "autosave");
        soft.assertThat(types.find("wp_block").orElseThrow().supports())
                .containsExactly("title", "excerpt", "editor", "revisions", "custom-fields", "autosave");
        soft.assertThat(types.find("page").orElseThrow().restBase()).contains("pages");
        soft.assertThat(types.find("wp_block").orElseThrow().restBase()).contains("blocks");
        soft.assertThat(types.all().subList(0, 4))
                .allSatisfy(type -> assertThat(type.showInRest()).as(type.key()).isTrue());
        soft.assertThat(types.find("attachment").orElseThrow().showInNavMenus()).isFalse();
        soft.assertThat(types.find("post").orElseThrow().showInNavMenus()).isTrue();
        soft.assertAll();
    }

    @Test
    void builtInStatusesHaveWordPressLabels() {
        SoftAssertions soft = new SoftAssertions();
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        statuses.all().forEach(status -> labels.put(status.name(), status.label()));
        soft.assertThat(labels)
                .containsExactly(
                        Map.entry("publish", "Published"),
                        Map.entry("future", "Scheduled"),
                        Map.entry("draft", "Draft"),
                        Map.entry("pending", "Pending"),
                        Map.entry("private", "Private"),
                        Map.entry("trash", "Trash"),
                        Map.entry("auto-draft", "auto-draft"),
                        Map.entry("inherit", "inherit"),
                        Map.entry("request-pending", "Pending"),
                        Map.entry("request-confirmed", "Confirmed"),
                        Map.entry("request-failed", "Failed"),
                        Map.entry("request-completed", "Completed"));
        soft.assertThat(statuses.find("publish").orElseThrow().labelCountSingular())
                .isEqualTo("Published <span class=\"count\">(%s)</span>");
        soft.assertThat(statuses.find("draft").orElseThrow().labelCountSingular())
                .isEqualTo("Draft <span class=\"count\">(%s)</span>");
        soft.assertAll();
    }

    @Test
    void theAnnotationGivesNamesSupportsAndLabels() {
        PostTypeDefinition book = types.find("book").orElseThrow();
        assertThat(book.labels())
                .containsEntry("name", "Books")
                .containsEntry("singular_name", "Book")
                .containsEntry("add_new_item", "Add Book")
                .containsEntry("all_items", "Books");
        assertThat(book.supports()).containsExactly("title", "editor", "author", "autosave");
        assertThat(book.publicly()).isTrue();
        assertThat(book.mapMetaCap()).isTrue();
    }

    @Test
    void attributesNotGivenTakeWordPressDefaults() {
        PostTypeDefinition book = types.find("book").orElseThrow();
        assertThat(book.excludeFromSearch()).isFalse();
        assertThat(book.showUi()).isTrue();
        assertThat(book.hasArchive()).isFalse();
        assertThat(book.description()).isEmpty();
        assertThat(types.find("note").orElseThrow().mapMetaCap()).isTrue();
    }

    @Test
    void attributesGivenOverrideWordPressDefaults() {
        PostTypeDefinition note = types.find("note").orElseThrow();
        assertThat(note.publicly()).isTrue();
        assertThat(note.excludeFromSearch()).isTrue();
        assertThat(note.showUi()).isFalse();
        assertThat(note.showInMenu()).isFalse();
        assertThat(note.hasArchive()).isTrue();
        assertThat(note.description()).isEqualTo("Short notes");
        assertThat(types.searchable()).doesNotContain("note");
    }

    @Test
    void builtInStatusesHaveWordPressFlags() {
        assertThat(statuses.all())
                .extracting(PostStatusDefinition::name)
                .containsExactly(
                        "publish",
                        "future",
                        "draft",
                        "pending",
                        "private",
                        "trash",
                        "auto-draft",
                        "inherit",
                        "request-pending",
                        "request-confirmed",
                        "request-failed",
                        "request-completed");
        // name -> public, protected, private, internal, publicly queryable, excluded from search, all list,
        // status list, floating
        Map<String, List<Boolean>> expected = Map.ofEntries(
                Map.entry("publish", List.of(true, false, false, false, true, false, true, true, false)),
                Map.entry("future", List.of(false, true, false, false, false, false, true, true, false)),
                Map.entry("draft", List.of(false, true, false, false, false, false, true, true, true)),
                Map.entry("pending", List.of(false, true, false, false, false, false, true, true, true)),
                Map.entry("private", List.of(false, false, true, false, false, false, true, true, false)),
                Map.entry("trash", List.of(false, false, false, true, false, true, false, true, false)),
                Map.entry("auto-draft", List.of(false, false, false, true, false, true, false, false, true)),
                Map.entry("inherit", List.of(false, false, false, true, false, false, false, false, false)),
                Map.entry("request-pending", List.of(false, false, false, true, false, false, false, false, false)),
                Map.entry("request-confirmed", List.of(false, false, false, true, false, false, false, false, false)),
                Map.entry("request-failed", List.of(false, false, false, true, false, false, false, false, false)),
                Map.entry("request-completed", List.of(false, false, false, true, false, false, false, false, false)));
        SoftAssertions soft = new SoftAssertions();
        expected.forEach((name, flags) -> {
            PostStatusDefinition status = statuses.find(name).orElseThrow();
            soft.assertThat(List.of(
                            status.publicly(),
                            status.protectedStatus(),
                            status.privateStatus(),
                            status.internal(),
                            status.publiclyQueryable(),
                            status.excludeFromSearch(),
                            status.showInAdminAllList(),
                            status.showInAdminStatusList(),
                            status.dateFloating()))
                    .as(name)
                    .isEqualTo(flags);
        });
        soft.assertAll();
        assertThat(statuses.find("draft").orElseThrow().labelCountPlural())
                .isEqualTo("Drafts <span class=\"count\">(%s)</span>");
        assertThat(statuses.find("auto-draft").orElseThrow().label()).isEqualTo("auto-draft");
    }

    @Test
    void statusSetsAreTheOnesWordPressUses() {
        assertThat(statuses.floating("draft")).isTrue();
        assertThat(statuses.floating("auto-draft")).isTrue();
        assertThat(statuses.floating("publish")).isFalse();
        assertThat(statuses.floating("approved")).isFalse();
        assertThat(statuses.publicStatuses()).containsExactly("publish");
        assertThat(statuses.privateStatuses()).containsExactly("private");
        assertThat(statuses.excludedFromSearch()).containsExactly("trash", "auto-draft");
        assertThat(statuses.visibility("publish")).isEqualTo(PostVisibility.PUBLIC);
        assertThat(statuses.visibility("private")).isEqualTo(PostVisibility.PRIVATE);
        assertThat(statuses.visibility("draft")).isEqualTo(PostVisibility.OTHER);
        assertThat(statuses.visibility("approved")).isEqualTo(PostVisibility.UNREGISTERED);
        assertThat(statuses.registered("inherit")).isTrue();
        assertThat(statuses.registered("approved")).isFalse();
    }

    /** A logged-out check on a single site, for mappings that need no user or option. */
    private static final class LoggedOut implements MapContext {

        @Override
        public boolean multisite() {
            return false;
        }

        @Override
        public Optional<UserId> user() {
            return Optional.empty();
        }

        @Override
        public Optional<SiteId> site() {
            return Optional.empty();
        }

        @Override
        public boolean isSuperAdmin() {
            return false;
        }

        @Override
        public boolean isSuperAdmin(UserId user) {
            return false;
        }

        @Override
        public boolean userCan(String capability) {
            return false;
        }

        @Override
        public Optional<JsonNode> siteOption(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<JsonNode> networkOption(String name) {
            return Optional.empty();
        }

        @Override
        public boolean disallowUnfilteredHtml() {
            return false;
        }
    }
}
