package org.quarkpress.restwp.runtime.wpv2;

import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.PageTemplates;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.taxonomy.PostFormats;
import org.quarkpress.taxonomy.TermRef;
import org.quarkpress.taxonomy.Terms;

/**
 * Writes prepared post requests, each in one transaction: a step that fails rolls back every step before it, the
 * post row included, where WordPress keeps what it wrote before the failure. The steps run in WordPress's order.
 */
@ApplicationScoped
class PostWriter {

    @Inject
    Posts posts;

    @Inject
    FeaturedImages featuredImages;

    @Inject
    PostFormats formats;

    @Inject
    PageTemplates templates;

    @Inject
    Terms terms;

    @Inject
    PostRepository rows;

    @Inject
    SiteContext site;

    @Inject
    SystemIdentity system;

    @Inject
    CurrentIdentityAssociation association;

    /**
     * Creates the post, then makes it sticky (a post asked to be), sets its featured image, format and template, and
     * replaces its terms of each taxonomy the request names (ids of no term of the taxonomy are skipped); answers the
     * post as stored at the end. The steps after the insert run as the system, as WordPress runs them without asking
     * again whether the caller may edit the new post: the create check, the assign check of the named terms
     * included, has passed. Any error rolls everything back: the posts module's refusal of the post is WordPress's
     * insert error (500 for {@code db_insert_error}, else 400).
     */
    @Transactional
    PostSnapshot create(PostRoutes routes, PostRequest prepared, RestRequest request) {
        PostId id;
        try {
            id = posts.create(prepared.changes()).id();
        } catch (PostException refused) {
            throw new RestException(insertError(refused));
        }
        SecurityIdentity caller = association.getIdentity();
        association.setIdentity(system.identity());
        try {
            // anything but a truthy sticky unsticks, which changes nothing on a new post
            if (prepared.sticky().orElse(false)) {
                posts.stick(id);
            }
            if (prepared.featuredMedia().isPresent()) {
                featuredImages.set(id, prepared.featuredMedia().getAsLong());
            }
            if (prepared.format().isPresent()) {
                formats.set(id, prepared.format().get());
            }
            if (prepared.template().isPresent()) {
                templates.set(id, prepared.template().get());
            }
            for (Map.Entry<String, List<Long>> entry : prepared.terms().entrySet()) {
                List<TermRef> refs = new ArrayList<>();
                entry.getValue().forEach(term -> refs.add(TermRef.id(term)));
                terms.setPostTerms(id, entry.getKey(), refs, false);
            }
        } catch (PostException refused) {
            throw new RestException(insertError(refused));
        } finally {
            association.setIdentity(caller);
        }
        return stored(id);
    }

    private PostSnapshot stored(PostId id) {
        return rows.findPost(site.siteId(), id).map(PostEntity::toSnapshot).orElseThrow();
    }

    /** The answer to a write the posts module refused: 500 for a database error, else 400, with its code. */
    static RestError insertError(PostException refused) {
        int status = refused.code().equals("db_insert_error") || refused.code().equals("db_update_error") ? 500 : 400;
        return RestError.of(refused.code(), refused.getMessage(), status);
    }
}
