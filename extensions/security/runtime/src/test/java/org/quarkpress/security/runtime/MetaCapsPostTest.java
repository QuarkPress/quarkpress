package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.CapObject;
import org.quarkpress.security.MetaCapabilityFilter;
import org.quarkpress.security.MetaCapabilityNames;
import org.quarkpress.security.MissingObject;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.PostVisibility;

class MetaCapsPostTest {

    private static final MetaCaps CAPS = new MetaCaps(List.of(), List.of(new BuiltInMetaCapabilityNames()));

    private static PostFacts post(long id, String status, long author) {
        return PostFacts.of(
                PostId.of(id),
                PostTypeCapabilities.POST,
                status,
                author == 0 ? Optional.empty() : Optional.of(UserId.of(author)));
    }

    private static PostFacts page(long id, String status, long author) {
        return PostFacts.of(PostId.of(id), PostTypeCapabilities.PAGE, status, Optional.of(UserId.of(author)));
    }

    private static Set<String> map(String cap, CapObject object, MapContext context) {
        return CAPS.map(cap, Optional.ofNullable(object), context);
    }

    @Test
    void editingOwnPostsDependsOnTheStatus() {
        FakeContext seven = FakeContext.single(7);
        assertThat(map("edit_post", post(1, "pending", 7), seven)).containsExactly("edit_posts");
        assertThat(map("edit_post", post(1, "draft", 7), seven)).containsExactly("edit_posts");
        assertThat(map("edit_post", post(1, "auto-draft", 7), seven)).containsExactly("edit_posts");
        assertThat(map("edit_post", post(1, "private", 7), seven)).containsExactly("edit_posts");
        assertThat(map("edit_post", post(1, "publish", 7), seven)).containsExactly("edit_published_posts");
        assertThat(map("edit_post", post(1, "future", 7), seven)).containsExactly("edit_published_posts");
        assertThat(map("edit_post", post(1, "in-review", 7), seven)).containsExactly("edit_posts");
    }

    @Test
    void trashedOwnPostsUseTheStatusBeforeTrashing() {
        FakeContext seven = FakeContext.single(7);
        assertThat(map("edit_post", post(1, "trash", 7).withStatusBeforeTrash("publish"), seven))
                .containsExactly("edit_published_posts");
        assertThat(map("edit_post", post(1, "trash", 7).withStatusBeforeTrash("pending"), seven))
                .containsExactly("edit_posts");
        assertThat(map("edit_post", post(1, "trash", 7), seven)).containsExactly("edit_posts");
        assertThat(map("delete_post", post(1, "trash", 7).withStatusBeforeTrash("future"), seven))
                .containsExactly("delete_published_posts");
        assertThat(map("edit_post", post(1, "trash", 9).withStatusBeforeTrash("private"), seven))
                .containsExactly("edit_others_posts");
        assertThat(map("delete_post", post(1, "trash", 9).withStatusBeforeTrash("private"), seven))
                .containsExactly("delete_others_posts");
    }

    @Test
    void editingOthersPostsNeedsTheOthersCapabilityAndAnExtraOneByStatus() {
        FakeContext seven = FakeContext.single(7);
        assertThat(map("edit_post", post(1, "draft", 9), seven)).containsExactly("edit_others_posts");
        assertThat(map("edit_post", post(1, "publish", 9), seven))
                .containsExactly("edit_others_posts", "edit_published_posts");
        assertThat(map("edit_post", post(1, "private", 9), seven))
                .containsExactly("edit_others_posts", "edit_private_posts");
        assertThat(map("edit_post", post(1, "publish", 0), seven))
                .containsExactly("edit_others_posts", "edit_published_posts");
        assertThat(map("edit_post", post(1, "draft", 0), FakeContext.single(0))).containsExactly("edit_others_posts");
    }

    @Test
    void deletingFollowsTheSameShape() {
        FakeContext seven = FakeContext.single(7);
        assertThat(map("delete_post", post(1, "draft", 7), seven)).containsExactly("delete_posts");
        assertThat(map("delete_post", post(1, "private", 7), seven)).containsExactly("delete_posts");
        assertThat(map("delete_post", post(1, "publish", 7), seven)).containsExactly("delete_published_posts");
        assertThat(map("delete_post", post(1, "private", 9), seven))
                .containsExactly("delete_others_posts", "delete_private_posts");
        assertThat(map("delete_post", post(1, "future", 9), seven))
                .containsExactly("delete_others_posts", "delete_published_posts");
    }

    @Test
    void theObjectsTypeDecidesTheNamesForPostAndPageForms() {
        FakeContext seven = FakeContext.single(7);
        assertThat(map("edit_post", page(2, "publish", 9), seven))
                .containsExactly("edit_others_pages", "edit_published_pages");
        assertThat(map("edit_page", post(1, "draft", 7), seven)).containsExactly("edit_posts");
        assertThat(map("delete_page", page(2, "draft", 7), seven)).containsExactly("delete_pages");
        assertThat(map("read_page", page(2, "private", 9), seven)).containsExactly("read_private_pages");
    }

    @Test
    void theFrontPageAndThePageForPostsNeedManageOptionsToDelete() {
        FakeContext context = FakeContext.single(7)
                .siteOption("page_on_front", IntNode.valueOf(2))
                .siteOption("page_for_posts", TextNode.valueOf("3"));
        assertThat(map("delete_post", page(2, "publish", 7), context)).containsExactly("manage_options");
        assertThat(map("delete_page", page(3, "draft", 9), context)).containsExactly("manage_options");
        assertThat(map("edit_post", page(2, "publish", 7), context)).containsExactly("edit_published_pages");
    }

    @Test
    void thePrivacyPageAddsThePrivacyCapability() {
        FakeContext single = FakeContext.single(7).siteOption("wp_page_for_privacy_policy", IntNode.valueOf(4));
        assertThat(map("edit_post", page(4, "draft", 7), single)).containsExactly("edit_pages", "manage_options");
        assertThat(map("delete_post", page(4, "draft", 9), single))
                .containsExactly("delete_others_pages", "manage_options");
        FakeContext network = FakeContext.network(7).siteOption("wp_page_for_privacy_policy", IntNode.valueOf(4));
        assertThat(map("edit_post", page(4, "draft", 7), network)).containsExactly("edit_pages", "manage_network");
        assertThat(map("read_post", page(4, "draft", 9), network))
                .containsExactly("edit_others_pages", "manage_network");
        assertThat(map("publish_post", page(4, "draft", 9), network)).containsExactly("publish_pages");
    }

    @Test
    void readingDependsOnTheEffectiveStatus() {
        FakeContext seven = FakeContext.single(7);
        assertThat(map("read_post", post(1, "publish", 9), seven)).containsExactly("read");
        assertThat(map("read_post", post(1, "publish", 9), FakeContext.single(0)))
                .containsExactly("read");
        assertThat(map("read_post", post(1, "draft", 7), seven)).containsExactly("read");
        assertThat(map("read_post", post(1, "private", 9), seven)).containsExactly("read_private_posts");
        assertThat(map("read_post", post(1, "future", 9), seven))
                .containsExactly("edit_others_posts", "edit_published_posts");
        assertThat(map("read_post", post(1, "draft", 9), seven)).containsExactly("edit_others_posts");
        assertThat(map("read_post", post(1, "trash", 9), seven)).containsExactly("edit_others_posts");
        PostFacts attachment = PostFacts.of(
                        PostId.of(5), PostTypeCapabilities.ATTACHMENT, "inherit", Optional.of(UserId.of(9)))
                .withEffectiveStatus("draft", PostVisibility.OTHER);
        assertThat(map("read_post", attachment, seven)).containsExactly("edit_others_posts");
        PostFacts unattached = PostFacts.of(
                        PostId.of(6), PostTypeCapabilities.ATTACHMENT, "inherit", Optional.of(UserId.of(9)))
                .withEffectiveStatus("publish", PostVisibility.PUBLIC);
        assertThat(map("read_post", unattached, seven)).containsExactly("read");
        assertThat(map("read_post", post(1, "in-review", 9), seven)).containsExactly("edit_others_posts");
    }

    @Test
    void blocksReadWithEditPostsAndUseTheirOwnPrivateNames() {
        FakeContext seven = FakeContext.single(7);
        PostFacts block = PostFacts.of(PostId.of(8), PostTypeCapabilities.BLOCK, "publish", Optional.of(UserId.of(9)));
        assertThat(map("read_post", block, seven)).containsExactly("edit_posts");
        PostFacts privateBlock =
                PostFacts.of(PostId.of(8), PostTypeCapabilities.BLOCK, "private", Optional.of(UserId.of(9)));
        assertThat(map("edit_post", privateBlock, seven)).containsExactly("edit_others_posts", "edit_private_blocks");
        assertThat(map("edit_block", privateBlock, seven)).containsExactly("edit_others_posts", "edit_private_blocks");
        assertThat(map("edit_blocks", null, seven)).containsExactly("edit_posts");
        assertThat(map("publish_blocks", null, seven)).containsExactly("publish_posts");
    }

    @Test
    void publishingNeedsThePublishCapabilityOfTheType() {
        FakeContext seven = FakeContext.single(7);
        assertThat(map("publish_post", post(1, "draft", 7), seven)).containsExactly("publish_posts");
        assertThat(map("publish_post", post(1, "pending", 9), seven)).containsExactly("publish_posts");
        assertThat(map("publish_post", page(2, "draft", 7), seven)).containsExactly("publish_pages");
    }

    @Test
    void missingObjectsAndUnregisteredTypesAreRefusedOrLimited() {
        FakeContext seven = FakeContext.single(7);
        for (String cap : List.of("edit_post", "delete_post", "read_post", "publish_post", "edit_page")) {
            assertThat(map(cap, null, seven)).as(cap).containsExactly("do_not_allow");
            assertThat(map(cap, new MissingObject(999), seven)).as(cap).containsExactly("do_not_allow");
            assertThat(map(cap, post(1, "draft", 7).withUnregisteredType(), seven))
                    .as(cap)
                    .containsExactly("edit_others_posts");
        }
    }

    @Test
    void typesThatDoNotMapMetaCapabilitiesNeedTheirOwnNames() {
        PostTypeCapabilities book = PostTypeCapabilities.of("book", "book", "books", false, Map.of());
        PostFacts facts = PostFacts.of(PostId.of(9), book, "publish", Optional.of(UserId.of(9)));
        FakeContext seven = FakeContext.single(7);
        assertThat(map("edit_post", facts, seven)).containsExactly("edit_book");
        assertThat(map("read_post", facts, seven)).containsExactly("read_book");
        assertThat(map("delete_post", facts, seven)).containsExactly("delete_book");
        assertThat(map("publish_post", facts, seven)).containsExactly("publish_books");
    }

    @Test
    void customMetaNamesMapAsTheirCoreName() {
        PostTypeCapabilities book = PostTypeCapabilities.of("book", "book", "books", true, Map.of());
        MetaCaps caps = new MetaCaps(
                List.of(),
                List.<MetaCapabilityNames>of(() -> Map.of("edit_book", "edit_post", "read_book", "read_post")));
        PostFacts facts = PostFacts.of(PostId.of(9), book, "publish", Optional.of(UserId.of(9)));
        assertThat(caps.map("edit_book", Optional.of(facts), FakeContext.single(7)))
                .containsExactly("edit_others_books", "edit_published_books");
        assertThat(caps.map("edit_book", Optional.of(post(1, "draft", 9)), FakeContext.single(7)))
                .containsExactly("edit_others_posts");
    }

    @Test
    void unknownNamesMapToThemselves() {
        assertThat(map("frobnicate", null, FakeContext.single(7))).containsExactly("frobnicate");
        assertThat(map("Edit_Posts", null, FakeContext.single(7))).containsExactly("Edit_Posts");
        assertThat(map("edit_posts", post(1, "draft", 7), FakeContext.single(7)))
                .containsExactly("edit_posts");
    }

    @Test
    void filtersRunAfterEveryMappingStepIncludingNestedOnes() {
        List<String> seen = new ArrayList<>();
        MetaCapabilityFilter record = (required, request) -> {
            seen.add(request.capability() + "=" + required);
            return required;
        };
        MetaCapabilityFilter forbidDelete = (required, request) -> {
            if (!request.capability().equals("delete_post")) {
                return required;
            }
            Set<String> more = new LinkedHashSet<>(required);
            more.add("do_not_allow");
            return more;
        };
        MetaCaps caps = new MetaCaps(List.of(record, forbidDelete), List.of());
        FakeContext seven = FakeContext.single(7);
        assertThat(caps.map("read_post", Optional.of(post(1, "draft", 9)), seven))
                .containsExactly("edit_others_posts");
        assertThat(seen).containsExactly("edit_post=[edit_others_posts]", "read_post=[edit_others_posts]");
        assertThat(caps.map("delete_post", Optional.of(post(1, "draft", 7)), seven))
                .containsExactly("delete_posts", "do_not_allow");
    }
}
