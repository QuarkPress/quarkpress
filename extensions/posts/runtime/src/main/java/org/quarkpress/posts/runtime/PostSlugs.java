package org.quarkpress.posts.runtime;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.Filters;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PostSlugFilter;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.SlugContext;

/**
 * Unique slugs against the site's stored posts, then the slug filters. Lookups are locking reads, so inside a longer
 * transaction they still see posts other writers committed (the site's post lock orders the writers).
 */
@ApplicationScoped
public class PostSlugs {

    @Inject
    PostRepository posts;

    @Inject
    PostTypes types;

    @Inject
    SiteContext site;

    @Inject
    @All
    List<InstanceHandle<PostSlugFilter>> filters;

    public String unique(
            SiteId siteId,
            String slug,
            Optional<PostId> post,
            Optional<String> storedSlug,
            String status,
            String type,
            Optional<PostId> parent) {
        if (UniqueSlugs.exempt(status, type)) {
            return slug;
        }
        String unique = UniqueSlugs.unique(
                slug,
                post,
                storedSlug,
                type,
                parent,
                types.hierarchical(type),
                site.options().string("permalink_structure", ""),
                lookup(siteId));
        SlugContext context = new SlugContext(siteId, post, type, status, parent, slug);
        return Filters.apply(Filters.ordered(filters), unique, (filter, value) -> filter.apply(value, context));
    }

    private UniqueSlugs.Lookup lookup(SiteId siteId) {
        Object site = TenantIds.param(siteId);
        return new UniqueSlugs.Lookup() {
            @Override
            public boolean anyType(String slug, Optional<PostId> exclude) {
                return taken("siteId = ?1 and slug = ?2 and id <> ?3", site, slug, excluded(exclude));
            }

            @Override
            public boolean sameType(String slug, String type, Optional<PostId> exclude) {
                return taken(
                        "siteId = ?1 and slug = ?2 and id <> ?3 and type = ?4", site, slug, excluded(exclude), type);
            }

            @Override
            public boolean sameTypeOrAttachmentUnder(
                    String slug, String type, Optional<PostId> parent, Optional<PostId> exclude) {
                String sameParent = parent.isPresent() ? "parentId = ?5" : "parentId is null";
                String where = "siteId = ?1 and slug = ?2 and id <> ?3 and (type = ?4 or type = 'attachment') and "
                        + sameParent;
                return parent.isPresent()
                        ? taken(
                                where,
                                site,
                                slug,
                                excluded(exclude),
                                type,
                                parent.get().value())
                        : taken(where, site, slug, excluded(exclude), type);
            }
        };
    }

    private boolean taken(String where, Object... params) {
        return posts.find(where, params)
                .withLock(LockModeType.PESSIMISTIC_READ)
                .firstResultOptional()
                .isPresent();
    }

    private static long excluded(Optional<PostId> post) {
        return post.map(PostId::value).orElse(-1L);
    }
}
