package org.quarkpress.restwp.runtime.wpv2;

import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.PageTemplates;
import org.quarkpress.posts.PostChanges;
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
 * post row included, where WordPress keeps what it wrote before the failure. The steps run in WordPress's order, which
 * differs between a create and an update.
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

    @Inject
    MetaFields metaFields;

    /** The revision of an update; a bean only while the revisions module is present. */
    @Inject
    Instance<RevisionHistory> revisions;

    /**
     * Creates the post, then makes it sticky (a post asked to be), sets its featured image, format and template,
     * replaces its terms of each taxonomy the request names (ids of no term of the taxonomy are skipped) and writes
     * the registered meta the request names; answers the post as stored at the end. The steps after the insert but the
     * meta run as the system, as WordPress runs them without asking again whether the caller may edit the new post:
     * the create check, the assign check of the named terms included, has passed; the meta is written as the caller,
     * whose permission each key checks. Any error rolls everything back: the posts module's refusal of the post is
     * WordPress's insert error (500 for {@code db_insert_error}, else 400), a meta key's error is the meta step's.
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
                // whether the value changed is not used: WordPress's posts controller ignores the answer too
                featuredImages.set(id, prepared.featuredMedia().getAsLong());
            }
            if (prepared.format().isPresent()) {
                formats.set(id, prepared.format().get());
            }
            if (prepared.template().isPresent()) {
                templates.set(id, prepared.template().get());
            }
            replaceTerms(id, prepared);
        } catch (PostException refused) {
            throw new RestException(insertError(refused));
        } finally {
            association.setIdentity(caller);
        }
        writeMeta(routes, id, prepared, request);
        return stored(id);
    }

    /**
     * Updates the post with the fields the request names, then, as WordPress's update orders them, sets its format and
     * featured image, sticks or unsticks it (when the request says), sets its template, replaces its terms of each
     * taxonomy the request names and writes the registered meta the request names; the revision is saved once at the
     * end, after every step, so it holds the meta written here, while the revisions module is present (none when the
     * post is unchanged since its latest revision). Dates the request cleared date the post now, floating when the
     * status it gets floats. The steps after the row but the meta run as the system, as WordPress runs them without
     * asking again whether the caller may edit the post; the meta is written as the caller. Any error rolls everything
     * back, and no revision is saved: the posts module's refusal is WordPress's update error (500 for a database
     * error, else 400), a meta key's error is the meta step's. Answers the post as stored at the end.
     */
    @Transactional
    PostSnapshot update(PostRoutes routes, PostSnapshot stored, PostRequest prepared, RestRequest request) {
        PostId id = stored.id();
        Supplier<PostSnapshot> work = () -> {
            write(stored, prepared);
            writeMeta(routes, id, prepared, request);
            return null;
        };
        if (revisions.isResolvable()) {
            revisions.get().revisingAfter(id, work);
        } else {
            work.get();
        }
        return stored(id);
    }

    private void write(PostSnapshot stored, PostRequest prepared) {
        PostId id = stored.id();
        PostChanges changes = prepared.changes();
        if (prepared.datesCleared()) {
            changes = changes.toBuilder().clearDates().build();
        }
        try {
            posts.update(id, changes);
        } catch (PostException refused) {
            throw new RestException(insertError(refused));
        }
        SecurityIdentity caller = association.getIdentity();
        association.setIdentity(system.identity());
        try {
            if (prepared.format().isPresent()) {
                formats.set(id, prepared.format().get());
            }
            if (prepared.featuredMedia().isPresent()) {
                // whether the value changed is not used: WordPress's posts controller ignores the answer too
                featuredImages.set(id, prepared.featuredMedia().getAsLong());
            }
            if (prepared.sticky().isPresent()) {
                if (prepared.sticky().get()) {
                    posts.stick(id);
                } else {
                    posts.unstick(id);
                }
            }
            if (prepared.template().isPresent()) {
                templates.set(id, prepared.template().get());
            }
            replaceTerms(id, prepared);
        } catch (PostException refused) {
            throw new RestException(insertError(refused));
        } finally {
            association.setIdentity(caller);
        }
    }

    /** Writes the registered meta the request names, as the caller; a key's error fails the write. */
    private void writeMeta(PostRoutes routes, PostId id, PostRequest prepared, RestRequest request) {
        if (prepared.meta().isPresent()) {
            metaFields.write(routes, id, prepared.meta().get(), request).ifPresent(error -> {
                throw new RestException(error);
            });
        }
    }

    /** Replaces the post's terms of each taxonomy the request names; ids of no term of the taxonomy are skipped. */
    private void replaceTerms(PostId id, PostRequest prepared) {
        for (Map.Entry<String, List<Long>> entry : prepared.terms().entrySet()) {
            List<TermRef> refs = new ArrayList<>();
            entry.getValue().forEach(term -> refs.add(TermRef.id(term)));
            terms.setPostTerms(id, entry.getKey(), refs, false);
        }
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
