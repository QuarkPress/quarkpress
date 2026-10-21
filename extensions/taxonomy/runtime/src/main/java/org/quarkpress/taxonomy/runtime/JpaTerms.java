package org.quarkpress.taxonomy.runtime;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;

/**
 * The terms service: capability checks first (WordPress's REST rules), then the site's post lock, then the write
 * path. A refused capability check or an input refusal raised before the first write does not mark the caller's
 * transaction for rollback; a refused lock does.
 */
@ApplicationScoped
public class JpaTerms implements Terms {

    @Inject
    SiteContext site;

    @Inject
    Taxonomies taxonomies;

    @Inject
    Acting acting;

    @Inject
    PostLocks locks;

    @Inject
    TermWrites writes;

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public TermSnapshot create(String taxonomy, TermChanges changes) {
        Objects.requireNonNull(taxonomy, "taxonomy");
        Objects.requireNonNull(changes, "changes");
        SiteId siteId = site.siteId();
        TaxonomyDefinition definition = taxonomies.require(taxonomy);
        acting.require(siteId, createCapability(definition), Optional.empty());
        locks.lock(siteId);
        return writes.insert(siteId, definition, changes);
    }

    /** Creating a term needs the taxonomy's edit_terms when it is hierarchical, its assign_terms when it is flat. */
    static String createCapability(TaxonomyDefinition taxonomy) {
        return taxonomy.capability(taxonomy.hierarchical() ? "edit_terms" : "assign_terms");
    }
}
