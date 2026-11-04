package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostSnapshot;

/** The preview links of WordPress's autosaves answers. */
class PreviewLinksTest {

    private static final Instant WHEN = Instant.parse("2026-01-10T10:00:00Z");

    private final PostRoutes posts =
            routes(PostTypeDefinition.builder("post").publicly(true).builtIn().build());

    private final PostRoutes pages = routes(PostTypeDefinition.builder("page")
            .publicly(true)
            .hierarchical(true)
            .builtIn()
            .build());

    /** WordPress's reusable blocks: built in, neither public nor publicly queryable. */
    private final PostRoutes blocksRoutes =
            routes(PostTypeDefinition.builder("wp_block").builtIn().build());

    /** A custom type that is publicly queryable without being public. */
    private final PostRoutes queryable =
            routes(PostTypeDefinition.builder("book").publiclyQueryable(true).build());

    private static PostRoutes routes(PostTypeDefinition type) {
        return new PostRoutes(
                type,
                "wp/v2",
                type.key() + "s",
                PostSchema.of(type, List.of(), JsonNodeFactory.instance.objectNode(), List.of("publish", "draft")),
                List.of());
    }

    private static PostSnapshot snapshot(long id, String type, String status) {
        return new PostSnapshot(
                PostId.of(id),
                SiteId.of(1),
                type,
                status,
                "slug-" + id,
                "Title",
                "",
                "",
                Optional.of(UserId.of(2)),
                Optional.empty(),
                0,
                Optional.of(WHEN),
                WHEN,
                WHEN,
                "open",
                "open",
                "",
                false,
                "guid-" + id,
                Map.of(),
                0);
    }

    private static PostSnapshot post(long id, String status) {
        return snapshot(id, "post", status);
    }

    private static PostSnapshot page(long id) {
        return snapshot(id, "page", "publish");
    }

    private static PostSnapshot block(long id) {
        return snapshot(id, "wp_block", "publish");
    }

    @Test
    void wordPressShapes() {
        assertThat(PreviewLinks.of(posts, post(7, "publish"), "http://site/?p=7", Optional.of("tok")))
                .isEqualTo("http://site/?p=7&preview_id=7&preview_nonce=tok&preview=true");
        assertThat(PreviewLinks.of(pages, page(9), "http://site/?page_id=9", Optional.of("tok")))
                .isEqualTo("http://site/?page_id=9&preview_id=9&preview_nonce=tok&preview=true");
        assertThat(PreviewLinks.of(posts, post(12, "draft"), "http://site/?p=12", Optional.empty()))
                .isEqualTo("http://site/?p=12&preview=true");
        assertThat(PreviewLinks.of(blocksRoutes, block(5), "http://site/?post_type=wp_block&p=5", Optional.of("t")))
                .isEqualTo("");
    }

    @Test
    void aLinkWithoutQueryStartsOne() {
        assertThat(PreviewLinks.of(posts, post(7, "publish"), "http://site/seven/", Optional.of("tok")))
                .isEqualTo("http://site/seven/?preview_id=7&preview_nonce=tok&preview=true");
        assertThat(PreviewLinks.of(posts, post(12, "draft"), "http://site/twelve/", Optional.empty()))
                .isEqualTo("http://site/twelve/?preview=true");
    }

    @Test
    void aPubliclyQueryableTypeIsViewable() {
        assertThat(PreviewLinks.of(
                        queryable, snapshot(3, "book", "publish"), "http://site/?book=slug-3", Optional.of("t")))
                .isEqualTo("http://site/?book=slug-3&preview_id=3&preview_nonce=t&preview=true");
        assertThat(PreviewLinks.of(blocksRoutes, block(5), "http://site/?post_type=wp_block&p=5", Optional.empty()))
                .isEqualTo("");
    }
}
