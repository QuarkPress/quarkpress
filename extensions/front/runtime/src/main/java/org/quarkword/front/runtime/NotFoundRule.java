package org.quarkword.front.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.quarkword.core.PostStatusDefinition;
import org.quarkword.core.SiteContext;
import org.quarkword.front.FrontFlags;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.QueriedObject;
import org.quarkword.front.Viewer;
import org.quarkword.posts.PostPermissions;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.PostStatuses;
import org.quarkword.posts.runtime.PostCapabilityFacts;
import org.quarkword.security.Memberships;

/**
 * WordPress's 404 decision once the main query ran ({@code handle_404}): a request the parse found not to exist is
 * left as it is; one that found posts is a 200, unless it is singular and asks for a content page ({@code page} not
 * empty and not {@code 0}, as the request parser gave it) that the post's content does not have (no {@code
 * <!--nextpage-->} at all, or a page beyond the markers' count plus one), or it is the posts page with a content page;
 * one that found nothing on a second or later page is a 404; one that found nothing otherwise is a 200 for the home
 * page, a search, a feed, an author archive whose {@code author} is the id of a member of the site, and a category,
 * tag, taxonomy or post type archive whose queried object exists, and a 404 for anything else. The markers counted
 * are the shown post's, which an armed preview has given the autosave's content. A 404 loses every flag but the 404
 * one, its post is not shown, and a queried post the viewer may not see is dropped, so a hidden page's 404 is a
 * missing page's.
 */
@ApplicationScoped
public class NotFoundRule {

    @Inject
    Memberships memberships;

    @Inject
    SiteContext site;

    @Inject
    PostPermissions permissions;

    @Inject
    PostStatuses statuses;

    @Inject
    PostCapabilityFacts facts;

    /** The request with its status: 200, or 404 with the not-found flags. */
    public FrontRequest apply(FrontRequest request, Viewer viewer) {
        FrontFlags flags = request.flags();
        if (flags.is404()) {
            return request.withQueriedObject(readable(request.queriedObject(), viewer));
        }
        if (flags.robots() || flags.favicon() || flags.sitemap()) {
            return request.withStatus(200);
        }
        List<PostSnapshot> posts = request.posts().posts();
        boolean found;
        if (!posts.isEmpty()) {
            found = true;
            Optional<String> page = request.vars().get("page").filter(value -> !value.isEmpty() && !value.equals("0"));
            if (flags.singular() && page.isPresent()) {
                String content = request.shownPost().orElse(posts.get(0)).content();
                found = content.contains(ContentPages.MARKER)
                        && FlagParser.phpInt(trimSlashes(page.get())) <= ContentPages.markers(content) + 1L;
            }
            if (flags.postsPage() && page.isPresent()) {
                found = false;
            }
        } else if (!flags.paged()) {
            found = (flags.author() && memberAuthor(request))
                    || ((flags.tag() || flags.category() || flags.tax() || flags.postTypeArchive())
                            && request.queriedObject().isPresent())
                    || flags.home()
                    || flags.search()
                    || flags.feed();
        } else {
            found = false;
        }
        if (found) {
            return request.withStatus(200);
        }
        return request.withFlags(FrontFlags.notFound())
                .withStatus(404)
                .withShown(Optional.empty(), request.shownAt())
                .withQueriedObject(readable(request.queriedObject(), viewer));
    }

    /**
     * The queried object of a request that is not found: a post the viewer may not see (its effective status is not
     * public and the viewer lacks {@code read_post}) is dropped, as if the path had named no post; anything else is
     * kept. A public post is kept for everyone, although a visitor who is not logged in has no {@code read_post}.
     */
    public Optional<QueriedObject> readable(Optional<QueriedObject> queried, Viewer viewer) {
        if (queried.orElse(null) instanceof QueriedObject.Post post) {
            boolean publicly = statuses.find(facts.of(post.post()).effectiveStatus())
                    .map(PostStatusDefinition::publicly)
                    .orElse(false);
            if (!publicly && !permissions.can(viewer.identity(), "read_post", post.post())) {
                return Optional.empty();
            }
        }
        return queried;
    }

    /**
     * Whether the author archive names a member of the site: the {@code author} variable a number (the found user's
     * id for an {@code author_name}) above 0, for a user with a membership here.
     */
    private boolean memberAuthor(FrontRequest request) {
        if (!(request.queriedObject().orElse(null) instanceof QueriedObject.Author author)) {
            return false;
        }
        boolean byName = !request.vars().get("author_name").orElse("").isEmpty();
        if (!byName && !numeric(keepIdChars(request.vars().get("author").orElse("")))) {
            return false;
        }
        return memberships.isMember(site.siteId(), author.user().id());
    }

    /** The {@code author} variable as the query normalises it: digits, commas and minus signs only. */
    private static String keepIdChars(String value) {
        StringBuilder kept = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || c == ',' || c == '-') {
                kept.append(c);
            }
        }
        return kept.toString();
    }

    /** PHP's {@code is_numeric} of a text holding only digits, commas and minus signs: an optional sign, then digits. */
    private static boolean numeric(String value) {
        int start = value.startsWith("-") ? 1 : 0;
        if (start == value.length()) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }
}
