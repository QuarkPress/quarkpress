package org.quarkpress.taxonomy.runtime;

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
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.posts.runtime.PostCapabilityFacts;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.taxonomy.PostFormats;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermChanges;

/**
 * Post formats as terms of the {@code post_format} taxonomy: the {@code edit_post} check first, then the site's post
 * lock, then the term write. The format term is created on first use by the module itself, so no caller needs the
 * taxonomy's capabilities.
 */
@ApplicationScoped
public class JpaPostFormats implements PostFormats {

    static final String TAXONOMY = "post_format";

    static final String PREFIX = "post-format-";

    @Inject
    SiteContext site;

    @Inject
    Taxonomies taxonomies;

    @Inject
    Acting acting;

    @Inject
    PostLocks locks;

    @Inject
    PostRepository posts;

    @Inject
    PostCapabilityFacts postFacts;

    @Inject
    TermLookups lookups;

    @Inject
    TermWrites writes;

    @Inject
    TermAssignments assignments;

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public String of(PostId post) {
        Objects.requireNonNull(post, "post");
        SiteId siteId = site.siteId();
        requirePost(siteId, post);
        for (TermId term : assignments.termsOf(siteId, post, TAXONOMY)) {
            Optional<String> slug = lookups.find(siteId, term, TAXONOMY).map(row -> row.slug);
            if (slug.isPresent() && slug.get().startsWith(PREFIX)) {
                return slug.get().substring(PREFIX.length());
            }
        }
        return SLUGS.get(0);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public void set(PostId post, String slug) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(slug, "slug");
        if (!SLUGS.contains(slug)) {
            throw new IllegalArgumentException("not a post format: " + slug);
        }
        SiteId siteId = site.siteId();
        TaxonomyDefinition definition = taxonomies.require(TAXONOMY);
        requireEditable(siteId, post);
        locks.lock(siteId);
        requireEditable(siteId, post);
        writes.written(() -> {
            if (slug.equals(SLUGS.get(0))) {
                assignments.remove(siteId, post, TAXONOMY, assignments.termsOf(siteId, post, TAXONOMY));
                return null;
            }
            String termSlug = PREFIX + slug;
            TermId term = lookups.bySlug(siteId, TAXONOMY, termSlug)
                    .map(row -> TermId.of(row.id))
                    .orElseGet(() -> writes.insert(
                                    siteId,
                                    definition,
                                    TermChanges.builder()
                                            .name(termSlug)
                                            .slug(termSlug)
                                            .build())
                            .id());
            assignments.set(siteId, post, definition, List.of(term), false);
            return null;
        });
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public boolean exists(String slug) {
        Objects.requireNonNull(slug, "slug");
        if (slug.equals(SLUGS.get(0)) || !SLUGS.contains(slug)) {
            return false;
        }
        return lookups.bySlug(site.siteId(), TAXONOMY, PREFIX + slug).isPresent();
    }

    /** The caller may edit the post, which exists on this site ({@code invalid_post} otherwise). */
    private void requireEditable(SiteId siteId, PostId post) {
        acting.require(siteId, "edit_post", Optional.of(postFacts.of(requirePost(siteId, post))));
    }

    private PostSnapshot requirePost(SiteId siteId, PostId post) {
        return posts.findPost(siteId, post)
                .map(PostEntity::toSnapshot)
                .orElseThrow(() -> PostException.invalidPost(post));
    }
}
