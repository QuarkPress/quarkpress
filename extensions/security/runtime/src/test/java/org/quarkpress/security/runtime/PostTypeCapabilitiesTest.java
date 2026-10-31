package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.PostVisibility;

class PostTypeCapabilitiesTest {

    @Test
    void namesComeFromTheCapabilityType() {
        PostTypeCapabilities book = PostTypeCapabilities.of("book", "book", "books", true, Map.of());
        assertThat(book.editPost()).isEqualTo("edit_book");
        assertThat(book.readPost()).isEqualTo("read_book");
        assertThat(book.deletePost()).isEqualTo("delete_book");
        assertThat(book.editOthersPosts()).isEqualTo("edit_others_books");
        assertThat(book.read()).isEqualTo("read");
        assertThat(book.deletePublishedPosts()).isEqualTo("delete_published_books");
        assertThat(book.createPosts()).isEqualTo("edit_books");
        assertThat(PostTypeCapabilities.of("story", "story", "stories", true, Map.of())
                        .publishPosts())
                .isEqualTo("publish_stories");
    }

    @Test
    void withoutMetaMappingTheExtraNamesAreAbsent() {
        PostTypeCapabilities book = PostTypeCapabilities.of("book", "book", "books", false, Map.of());
        assertThat(book.read()).isNull();
        assertThat(book.editPublishedPosts()).isNull();
        assertThat(book.editPosts()).isEqualTo("edit_books");
    }

    @Test
    void builtInTypesHaveWordPressNames() {
        assertThat(PostTypeCapabilities.POST.editPublishedPosts()).isEqualTo("edit_published_posts");
        assertThat(PostTypeCapabilities.PAGE.readPrivatePosts()).isEqualTo("read_private_pages");
        assertThat(PostTypeCapabilities.PAGE.read()).isEqualTo("read");
        assertThat(PostTypeCapabilities.ATTACHMENT.createPosts()).isEqualTo("upload_files");
        assertThat(PostTypeCapabilities.ATTACHMENT.editOthersPosts()).isEqualTo("edit_others_posts");
        assertThat(PostTypeCapabilities.BLOCK.read()).isEqualTo("edit_posts");
        assertThat(PostTypeCapabilities.BLOCK.createPosts()).isEqualTo("publish_posts");
        assertThat(PostTypeCapabilities.BLOCK.publishPosts()).isEqualTo("publish_blocks");
        assertThat(PostTypeCapabilities.BLOCK.editPrivatePosts()).isEqualTo("edit_private_blocks");
        assertThat(PostTypeCapabilities.BLOCK.editOthersPosts()).isEqualTo("edit_others_posts");
        assertThat(PostTypeCapabilities.BLOCK.editPost()).isEqualTo("edit_block");
    }

    @Test
    void builtInStatusesHaveTheirVisibility() {
        assertThat(PostVisibility.ofBuiltInStatus("publish")).isEqualTo(PostVisibility.PUBLIC);
        assertThat(PostVisibility.ofBuiltInStatus("private")).isEqualTo(PostVisibility.PRIVATE);
        assertThat(PostVisibility.ofBuiltInStatus("future")).isEqualTo(PostVisibility.OTHER);
        assertThat(PostVisibility.ofBuiltInStatus("inherit")).isEqualTo(PostVisibility.OTHER);
        assertThat(PostVisibility.ofBuiltInStatus("in-review")).isEqualTo(PostVisibility.UNREGISTERED);
    }

    @Test
    void theNamesMapIsWhatIsEnforcedInWordPresssOrder() {
        for (PostTypeCapabilities type : List.of(
                PostTypeCapabilities.POST,
                PostTypeCapabilities.PAGE,
                PostTypeCapabilities.ATTACHMENT,
                PostTypeCapabilities.BLOCK,
                PostTypeCapabilities.of("book", "book", "books", false, Map.of("read", "read_books")))) {
            Map<String, String> names = type.names();
            assertThat(names.get("edit_post")).as(type.name()).isEqualTo(type.editPost());
            assertThat(names.get("edit_posts")).as(type.name()).isEqualTo(type.editPosts());
            assertThat(names.get("publish_posts")).as(type.name()).isEqualTo(type.publishPosts());
            assertThat(names.get("read")).as(type.name()).isEqualTo(type.read());
            assertThat(names.get("create_posts")).as(type.name()).isEqualTo(type.createPosts());
            assertThat(names.keySet()).as(type.name()).last().isEqualTo("create_posts");
        }
        assertThat(PostTypeCapabilities.BLOCK.names().keySet())
                .containsExactly(
                        "edit_post",
                        "read_post",
                        "delete_post",
                        "edit_posts",
                        "edit_others_posts",
                        "delete_posts",
                        "publish_posts",
                        "read_private_posts",
                        "read",
                        "delete_private_posts",
                        "delete_published_posts",
                        "delete_others_posts",
                        "edit_private_posts",
                        "edit_published_posts",
                        "create_posts");
        // a type that does not map meta capabilities: its own override comes after the computed names
        assertThat(PostTypeCapabilities.of("book", "book", "books", false, Map.of("read", "read_books"))
                        .names()
                        .keySet())
                .containsExactly(
                        "edit_post",
                        "read_post",
                        "delete_post",
                        "edit_posts",
                        "edit_others_posts",
                        "delete_posts",
                        "publish_posts",
                        "read_private_posts",
                        "read",
                        "create_posts");
        assertThatThrownBy(() -> PostTypeCapabilities.POST.names().put("read", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNamesMapThatDisagreesWithTheNamesIsRefused() {
        PostTypeCapabilities post = PostTypeCapabilities.POST;
        Map<String, String> names = new LinkedHashMap<>(post.names());
        names.put("publish_posts", "publish_everything");
        assertThatThrownBy(() -> new PostTypeCapabilities(
                        post.name(),
                        post.mapMetaCap(),
                        post.editPost(),
                        post.readPost(),
                        post.deletePost(),
                        post.editPosts(),
                        post.editOthersPosts(),
                        post.deletePosts(),
                        post.publishPosts(),
                        post.readPrivatePosts(),
                        post.read(),
                        post.deletePrivatePosts(),
                        post.deletePublishedPosts(),
                        post.deleteOthersPosts(),
                        post.editPrivatePosts(),
                        post.editPublishedPosts(),
                        post.createPosts(),
                        null,
                        null,
                        null,
                        names))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publish_posts");
    }
}
