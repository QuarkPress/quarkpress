package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
}
