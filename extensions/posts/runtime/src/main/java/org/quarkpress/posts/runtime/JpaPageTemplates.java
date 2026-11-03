package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PageTemplates;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;

/**
 * The page template on the {@code _wp_page_template} meta. A write checks {@code edit_post} on the post it finds,
 * takes the site's post lock and the row lock and checks again; the value goes through {@link ProtectedMeta}, so the
 * post's modification date and version stay. Refusals come before any lock.
 */
@ApplicationScoped
public class JpaPageTemplates implements PageTemplates {

    @Inject
    SiteContext site;

    @Inject
    PostRepository posts;

    @Inject
    PostLocks siteLocks;

    @Inject
    Acting acting;

    @Inject
    PostCapabilityFacts facts;

    @Inject
    ProtectedMeta meta;

    /** The stored template as text; {@code ""} when absent or not a scalar. */
    public static String template(PostSnapshot post) {
        List<JsonNode> values = Objects.requireNonNull(post, "post").meta(KEY);
        return values.isEmpty() ? "" : ProtectedMeta.scalarText(values.get(0)).orElse("");
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public void set(PostId post, String template) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(template, "template");
        SiteId siteId = site.siteId();
        PostEntity unlocked = posts.findPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(unlocked.toSnapshot())));
        siteLocks.lock(siteId);
        PostEntity row = posts.lockPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(row.toSnapshot())));
        meta.put(siteId, row, KEY, TextNode.valueOf(template));
    }
}
