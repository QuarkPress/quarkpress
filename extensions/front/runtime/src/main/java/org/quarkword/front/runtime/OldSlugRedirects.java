package org.quarkword.front.runtime;

import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteContext;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.Permalinks;
import org.quarkword.front.QueryVars;
import org.quarkword.front.Viewer;
import org.quarkword.posts.PostLookups;
import org.quarkword.posts.PostLookups.DateParts;
import org.quarkword.posts.PostTypes;
import org.quarkword.posts.runtime.PostEntity;
import org.quarkword.posts.runtime.PostRepository;

/**
 * WordPress's old slug redirect ({@code wp_old_slug_redirect}), decided before the canonical redirect: a request that
 * was not found and names a post ({@code name} not empty) is sent with a 301 to the post of the requested type that
 * once had that slug, narrowed by the requested date parts, or else (when a date part is given) to the post with that
 * slug that was once dated on the requested day, or that once had both. The type is the requested {@code post_type},
 * else {@code attachment} for an attachment request, {@code page} for a page path, {@code post} otherwise; a
 * hierarchical type is never redirected. No status is checked: the target is the post's permalink as the viewer sees it
 * (plain for a post the viewer may not see), with {@code page/N} after it for a list page above 1.
 */
@RequestScoped
public class OldSlugRedirects {

    @Inject
    PostLookups lookups;

    @Inject
    PostTypes types;

    @Inject
    PostRepository posts;

    @Inject
    SiteContext site;

    @Inject
    Permalinks permalinks;

    @Inject
    CurrentIdentityAssociation identities;

    /**
     * The old slug redirect of {@code request}, or empty, as {@code viewer} sees the site (a preview session's viewer
     * may differ from the request's own identity).
     */
    public Optional<Redirect> decide(FrontRequest request, Viewer viewer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(viewer, "viewer");
        if (!request.flags().is404() || DefaultFrontResolver.ENDING.contains(request.status())) {
            return Optional.empty();
        }
        SecurityIdentity previous = identities.getIdentity();
        identities.setIdentity(viewer.identity());
        try {
            return decide(request);
        } finally {
            identities.setIdentity(previous);
        }
    }

    private Optional<Redirect> decide(FrontRequest request) {
        QueryVars vars = request.queryVars();
        String name = vars.get("name").orElse("");
        if (name.isEmpty()) {
            return Optional.empty();
        }
        String type;
        if (truthy(vars.get("post_type").orElse(""))) {
            type = vars.get("post_type").orElseThrow();
        } else if (truthy(vars.get("attachment").orElse(""))) {
            type = "attachment";
        } else if (truthy(vars.get("pagename").orElse(""))) {
            type = "page";
        } else {
            type = "post";
        }
        if (types.hierarchical(type)) {
            return Optional.empty();
        }
        DateParts date = DateParts.of(number(vars, "year"), number(vars, "monthnum"), number(vars, "day"));
        Optional<PostId> found = lookups.firstByOldSlug(type, name, date);
        if (found.isEmpty()) {
            found = lookups.firstByOldDate(type, name, date);
            if (found.isEmpty()) {
                found = lookups.firstByOldSlugAndDate(type, name, date);
            }
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> link = posts.findPost(site.siteId(), found.get())
                .map(PostEntity::toSnapshot)
                .map(permalinks::post);
        if (link.isEmpty()) {
            return Optional.empty();
        }
        String target = link.get();
        long paged = number(vars, "paged");
        if (paged > 1) {
            target = trailingSlash(PhpUrls.trailingslashit(target) + "page/" + paged);
        }
        return Optional.of(Redirect.permanent(target));
    }

    private String trailingSlash(String url) {
        return permalinks.trailingSlashes() ? PhpUrls.trailingslashit(url) : PhpUrls.untrailingslashit(url);
    }

    private static long number(QueryVars vars, String name) {
        return FlagParser.phpInt(vars.get(name).orElse(""));
    }

    private static boolean truthy(String value) {
        return !value.isEmpty() && !value.equals("0");
    }
}
