package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.IntNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.MetaCapabilityFilter;
import org.quarkpress.security.MetaCapabilityNames;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.PostVisibility;

class MetaCapsPostFixTest {

    private static final MetaCaps CAPS = new MetaCaps(List.of(), List.of(new BuiltInMetaCapabilityNames()));

    @Test
    void pageFormsOnATypeWithoutMetaMappingAreNotSatisfiedByASameNamedCapability() {
        PostTypeCapabilities book = PostTypeCapabilities.of("book", "book", "books", false, Map.of());
        PostFacts facts = PostFacts.of(PostId.of(9), book, "draft", Optional.of(UserId.of(7)));
        FakeContext context = FakeContext.single(7).holding("edit_page", "read_page", "delete_page");
        for (String cap : List.of("edit_page", "read_page", "delete_page")) {
            assertThat(CAPS.map(cap, Optional.of(facts), context)).as(cap).containsExactly("");
        }
    }

    @Test
    void pageFormOverridesStayOnTheType() {
        PostTypeCapabilities book =
                PostTypeCapabilities.of("book", "book", "books", false, Map.of("edit_page", "edit_books"));
        PostFacts facts = PostFacts.of(PostId.of(9), book, "draft", Optional.of(UserId.of(7)));
        assertThat(CAPS.map("edit_page", Optional.of(facts), FakeContext.single(7)))
                .containsExactly("edit_books");
    }

    @Test
    void theFrontPageBranchShowsFiltersTheRequestedName() {
        PostTypeCapabilities book = PostTypeCapabilities.of("book", "book", "books", false, Map.of());
        PostFacts facts = PostFacts.of(PostId.of(12), book, "publish", Optional.of(UserId.of(7)));
        List<String> seen = new ArrayList<>();
        MetaCapabilityFilter record = (required, request) -> {
            seen.add(request.capability());
            return required;
        };
        MetaCaps caps = new MetaCaps(List.of(record), List.of());
        FakeContext context = FakeContext.single(7).siteOption("page_on_front", IntNode.valueOf(12));
        assertThat(caps.map("delete_post", Optional.of(facts), context)).containsExactly("manage_options");
        assertThat(seen).containsExactly("delete_post");
    }

    @Test
    void requestStatusesAreRegisteredAndNotPublic() {
        for (String status : List.of("request-pending", "request-confirmed", "request-failed", "request-completed")) {
            assertThat(PostVisibility.ofBuiltInStatus(status)).as(status).isEqualTo(PostVisibility.OTHER);
        }
    }

    @Test
    void attachmentsResolveTheirStatusAsWordPressDoes() {
        PostFacts draft = PostFacts.of(PostId.of(5), PostTypeCapabilities.ATTACHMENT, "draft", Optional.empty());
        assertThat(draft.effectiveStatus()).isEqualTo("publish");
        assertThat(draft.effectiveVisibility()).isEqualTo(PostVisibility.PUBLIC);
        PostFacts priv = PostFacts.of(PostId.of(5), PostTypeCapabilities.ATTACHMENT, "private", Optional.empty());
        assertThat(priv.effectiveVisibility()).isEqualTo(PostVisibility.PRIVATE);
        PostFacts unattached = PostFacts.ofAttachment(PostId.of(5), "inherit", Optional.empty(), Optional.empty());
        assertThat(unattached.effectiveStatus()).isEqualTo("publish");
        assertThat(unattached.effectiveVisibility()).isEqualTo(PostVisibility.PUBLIC);
        PostFacts parent = PostFacts.of(PostId.of(1), PostTypeCapabilities.POST, "draft", Optional.of(UserId.of(9)));
        PostFacts attached = PostFacts.ofAttachment(PostId.of(5), "inherit", Optional.empty(), Optional.of(parent));
        assertThat(attached.effectiveStatus()).isEqualTo("draft");
        assertThat(attached.effectiveVisibility()).isEqualTo(PostVisibility.OTHER);
    }

    @Test
    void conflictingCustomMetaNamesFail() {
        MetaCapabilityNames a = () -> Map.of("edit_thing", "edit_post");
        MetaCapabilityNames b = () -> Map.of("edit_thing", "read_post");
        assertThatThrownBy(() -> new MetaCaps(List.of(), List.of(a, b)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("edit_thing");
        new MetaCaps(List.of(), List.of(a, () -> Map.of("edit_thing", "edit_post")));
    }

    @Test
    void aMappingTypeMustHaveAllTheMappingNames() {
        PostTypeCapabilities t = PostTypeCapabilities.of("b", "b", "bs", true, Map.of());
        assertThatThrownBy(() -> new PostTypeCapabilities(
                        t.name(),
                        true,
                        t.editPost(),
                        t.readPost(),
                        t.deletePost(),
                        t.editPosts(),
                        t.editOthersPosts(),
                        t.deletePosts(),
                        t.publishPosts(),
                        t.readPrivatePosts(),
                        null,
                        t.deletePrivatePosts(),
                        t.deletePublishedPosts(),
                        t.deleteOthersPosts(),
                        t.editPrivatePosts(),
                        t.editPublishedPosts(),
                        t.createPosts(),
                        null,
                        null,
                        null))
                .isInstanceOf(NullPointerException.class);
    }

    private static PostFacts trashedParent(String before) {
        PostFacts trashed = PostFacts.of(PostId.of(1), PostTypeCapabilities.POST, "trash", Optional.of(UserId.of(9)));
        return before == null ? trashed : trashed.withStatusBeforeTrash(before);
    }

    @Test
    void anAttachmentOfATrashedParentTakesTheStatusBeforeTrashing() {
        FakeContext subscriber = FakeContext.single(7);
        PostFacts published = PostFacts.ofAttachment(
                PostId.of(5), "inherit", Optional.empty(), Optional.of(trashedParent("publish")));
        assertThat(published.effectiveStatus()).isEqualTo("publish");
        assertThat(published.effectiveVisibility()).isEqualTo(PostVisibility.PUBLIC);
        assertThat(CAPS.map("read_post", Optional.of(published), subscriber)).containsExactly("read");
        PostFacts priv = PostFacts.ofAttachment(
                PostId.of(5), "inherit", Optional.empty(), Optional.of(trashedParent("private")));
        assertThat(priv.effectiveVisibility()).isEqualTo(PostVisibility.PRIVATE);
        assertThat(CAPS.map("read_post", Optional.of(priv), subscriber)).containsExactly("read_private_posts");
        PostFacts none =
                PostFacts.ofAttachment(PostId.of(5), "inherit", Optional.empty(), Optional.of(trashedParent(null)));
        assertThat(none.effectiveStatus()).isEqualTo("publish");
        assertThat(none.effectiveVisibility()).isEqualTo(PostVisibility.PUBLIC);
    }

    @Test
    void anAttachmentThatIsItsOwnParentCountsAsPublished() {
        PostFacts self = PostFacts.of(PostId.of(5), PostTypeCapabilities.ATTACHMENT, "inherit", Optional.empty());
        PostFacts attachment = PostFacts.ofAttachment(PostId.of(5), "inherit", Optional.empty(), Optional.of(self));
        assertThat(attachment.effectiveStatus()).isEqualTo("publish");
        assertThat(attachment.effectiveVisibility()).isEqualTo(PostVisibility.PUBLIC);
    }
}
