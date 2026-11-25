package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.front.runtime.Redirect;
import org.quarkword.posts.PostType;

/**
 * The 404 guess never names a post a visitor may not see: only published posts of viewable types that are not left
 * out of searches are guessed (FR20.4), whoever asks.
 */
class GuessDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(
            GuessDbTest.class, RedirectCast.class, FrontCast.class, BookType.class, GenreTaxonomy.class, Hidden.class);

    /** A public type left out of searches. */
    @PostType(key = "unsearched", publicly = true, excludeFromSearch = true, supports = "title")
    static final class Hidden {}

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Test
    void neverGuessesNonViewable() throws Exception {
        RedirectCast cast = new RedirectCast(dataSource);
        cast.post("post", "trash", "trashed-post", null, "2025-11-20T08:00:00Z");
        cast.post("post", "pending", "pending-post", null, "2025-11-20T08:00:00Z");
        cast.post("wp_block", "publish", "pattern-block", null, "2025-11-20T08:00:00Z");
        cast.post("unsearched", "publish", "unsearched-one", null, "2025-11-20T08:00:00Z");
        for (String prefix : new String[] {"dra", "secr", "com", "tras", "pend", "patt", "unse", "draft-sh"}) {
            assertThat(cast.redirect("/" + prefix + "/")).as(prefix).isEmpty();
            assertThat(cast.redirect("/" + prefix + "/", RedirectCast.as(cast.alice)))
                    .as(prefix + " as alice")
                    .isEmpty();
        }
        // a published post is guessed for everyone
        assertThat(cast.redirect("/hello-w/"))
                .isEqualTo(Optional.of(new Redirect(301, "http://wp.test/2025/11/20/hello-world/")));
    }
}
