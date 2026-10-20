package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.PostVisibility;

class PostCapabilityFactsTest {

    static final PostTypes TYPES = new RegisteredPostTypes(withBook());
    static final PostStatuses STATUSES = new RegisteredPostStatuses(withArchived());
    static final Instant AT = Instant.parse("2026-01-15T12:00:00Z");

    private static List<PostTypeDefinition> withBook() {
        List<PostTypeDefinition> types = new ArrayList<>(BuiltInPostTypes.all());
        types.add(PostTypeDefinition.builder("book")
                .publicly(true)
                .capabilityType("book")
                .mapMetaCap(true)
                .build());
        return types;
    }

    private static List<PostStatusDefinition> withArchived() {
        List<PostStatusDefinition> statuses = new ArrayList<>(BuiltInPostStatuses.all());
        statuses.add(PostStatusDefinition.builder("archived").publicly(true).build());
        return statuses;
    }

    private static PostSnapshot post(
            long id, String type, String status, long parent, Map<String, List<JsonNode>> meta) {
        return new PostSnapshot(
                PostId.of(id),
                SiteId.of(1),
                type,
                status,
                "",
                "t",
                "",
                "",
                Optional.of(UserId.of(5)),
                parent == 0 ? Optional.empty() : Optional.of(PostId.of(parent)),
                0,
                Optional.of(AT),
                AT,
                AT,
                "open",
                "open",
                "",
                false,
                "g",
                meta,
                0);
    }

    private static PostFacts facts(PostSnapshot post, PostSnapshot... others) {
        Map<PostId, PostSnapshot> byId = new HashMap<>();
        for (PostSnapshot other : others) {
            byId.put(other.id(), other);
        }
        return PostCapabilityFacts.facts(post, TYPES, STATUSES, id -> Optional.ofNullable(byId.get(id)));
    }

    @Test
    void aPublishedPostIsPublicWithItsTypeNames() {
        PostFacts facts = facts(post(1, "post", "publish", 0, Map.of()));
        assertThat(facts.type()).contains(PostTypeCapabilities.POST);
        assertThat(facts.status()).isEqualTo("publish");
        assertThat(facts.author()).contains(UserId.of(5));
        assertThat(facts.effectiveStatus()).isEqualTo("publish");
        assertThat(facts.effectiveVisibility()).isEqualTo(PostVisibility.PUBLIC);
    }

    @Test
    void aRegisteredCustomStatusHasItsRegisteredVisibility() {
        assertThat(facts(post(1, "post", "archived", 0, Map.of())).effectiveVisibility())
                .isEqualTo(PostVisibility.PUBLIC);
        assertThat(facts(post(1, "post", "approved", 0, Map.of())).effectiveVisibility())
                .isEqualTo(PostVisibility.UNREGISTERED);
        assertThat(facts(post(1, "post", "draft", 0, Map.of())).effectiveVisibility())
                .isEqualTo(PostVisibility.OTHER);
    }

    @Test
    void anUnregisteredTypeHasNoTypeNames() {
        assertThat(facts(post(1, "thing", "publish", 0, Map.of())).type()).isEmpty();
        assertThat(facts(post(1, "book", "publish", 0, Map.of()))
                        .type()
                        .orElseThrow()
                        .editPost())
                .isEqualTo("edit_book");
    }

    @Test
    void aTrashedPostCarriesItsStatusBeforeTrashUnlessEmpty() {
        assertThat(facts(post(
                                1,
                                "post",
                                "trash",
                                0,
                                Map.of("_wp_trash_meta_status", List.of(TextNode.valueOf("publish")))))
                        .statusBeforeTrash())
                .contains("publish");
        assertThat(facts(post(1, "post", "trash", 0, Map.of("_wp_trash_meta_status", List.of(TextNode.valueOf("")))))
                        .statusBeforeTrash())
                .isEmpty();
        assertThat(facts(post(1, "post", "trash", 0, Map.of("_wp_trash_meta_status", List.of(TextNode.valueOf("0")))))
                        .statusBeforeTrash())
                .isEmpty();
        assertThat(facts(post(1, "post", "trash", 0, Map.of())).statusBeforeTrash())
                .isEmpty();
    }

    @Test
    void anAttachmentInheritsFromItsParent() {
        PostSnapshot privateParent = post(2, "post", "private", 0, Map.of());
        assertThat(facts(post(1, "attachment", "inherit", 2, Map.of()), privateParent)
                        .effectiveStatus())
                .isEqualTo("private");
        assertThat(facts(post(1, "attachment", "inherit", 0, Map.of())).effectiveStatus())
                .isEqualTo("publish");
        assertThat(facts(post(1, "attachment", "inherit", 1, Map.of())).effectiveStatus())
                .isEqualTo("publish");
        assertThat(facts(post(1, "attachment", "inherit", 9, Map.of())).effectiveStatus())
                .isEqualTo("publish");
        PostSnapshot trashedParent =
                post(3, "post", "trash", 0, Map.of("_wp_trash_meta_status", List.of(TextNode.valueOf("draft"))));
        assertThat(facts(post(1, "attachment", "inherit", 3, Map.of()), trashedParent)
                        .effectiveStatus())
                .isEqualTo("draft");
        PostSnapshot archivedBeforeTrash =
                post(4, "post", "trash", 0, Map.of("_wp_trash_meta_status", List.of(TextNode.valueOf("archived"))));
        PostFacts viaArchived = facts(post(1, "attachment", "inherit", 4, Map.of()), archivedBeforeTrash);
        assertThat(viaArchived.effectiveStatus()).isEqualTo("archived");
        assertThat(viaArchived.effectiveVisibility()).isEqualTo(PostVisibility.PUBLIC);
        assertThat(facts(post(1, "attachment", "private", 2, Map.of()), privateParent)
                        .effectiveStatus())
                .isEqualTo("private");
    }

    @Test
    void passwordsArePlainAndExact() {
        PostSnapshot open = post(1, "post", "publish", 0, Map.of());
        assertThat(org.quarkpress.posts.PostPasswords.required(open)).isFalse();
        assertThat(org.quarkpress.posts.PostPasswords.matches(open, "")).isFalse();
        PostSnapshot locked = new PostSnapshot(
                open.id(),
                open.site(),
                open.type(),
                open.status(),
                open.slug(),
                open.title(),
                open.content(),
                open.excerpt(),
                open.author(),
                open.parent(),
                0,
                open.publishedAt(),
                AT,
                AT,
                "open",
                "open",
                " Secret ",
                false,
                "g",
                Map.of(),
                0);
        PostSnapshot zero = new PostSnapshot(
                open.id(),
                open.site(),
                open.type(),
                open.status(),
                open.slug(),
                open.title(),
                open.content(),
                open.excerpt(),
                open.author(),
                open.parent(),
                0,
                open.publishedAt(),
                AT,
                AT,
                "open",
                "open",
                "0",
                false,
                "g",
                Map.of(),
                0);
        assertThat(org.quarkpress.posts.PostPasswords.required(zero)).isFalse();
        assertThat(org.quarkpress.posts.PostPasswords.matches(zero, "0")).isFalse();
        assertThat(org.quarkpress.posts.PostPasswords.required(locked)).isTrue();
        assertThat(org.quarkpress.posts.PostPasswords.matches(locked, " Secret "))
                .isTrue();
        assertThat(org.quarkpress.posts.PostPasswords.matches(locked, "Secret")).isFalse();
        assertThat(org.quarkpress.posts.PostPasswords.matches(locked, " secret "))
                .isFalse();
    }
}
