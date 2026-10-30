package org.quarkpress.taxonomy.runtime;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.TermId;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.posts.runtime.PostCapabilityFacts;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.TaxonomyCapabilities;
import org.quarkpress.taxonomy.PostTermsAccess;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermPermissions;

/**
 * Term capability checks answered by security's {@code Capabilities} with the facts of stored terms and posts, read
 * without capability checks of their own: the facade answers the question and never returns the term or the post.
 */
@ApplicationScoped
public class JpaTermPermissions implements TermPermissions {

    @Inject
    Capabilities capabilities;

    @Inject
    Taxonomies taxonomies;

    @Inject
    TermRepository terms;

    @Inject
    TermCapabilityFacts termFacts;

    @Inject
    PostRepository posts;

    @Inject
    PostCapabilityFacts postFacts;

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Inject
    SiteContext site;

    @Inject
    Acting acting;

    @Override
    public boolean canCreate(String taxonomy) {
        return canCreate(acting.identity(), taxonomy);
    }

    @Override
    public boolean canCreate(SecurityIdentity identity, String taxonomy) {
        Objects.requireNonNull(taxonomy, "taxonomy");
        return taxonomyCan(
                identity,
                taxonomy,
                taxonomies.hierarchical(taxonomy)
                        ? TaxonomyCapabilities::editTerms
                        : TaxonomyCapabilities::assignTerms);
    }

    @Override
    public boolean canEdit(TermId term) {
        return canEdit(acting.identity(), term);
    }

    @Override
    @Transactional
    public boolean canEdit(SecurityIdentity identity, TermId term) {
        return termCan(identity, "edit_term", term);
    }

    @Override
    public boolean canDelete(TermId term) {
        return canDelete(acting.identity(), term);
    }

    @Override
    @Transactional
    public boolean canDelete(SecurityIdentity identity, TermId term) {
        return termCan(identity, "delete_term", term);
    }

    @Override
    public boolean canEditTerms(String taxonomy) {
        return canEditTerms(acting.identity(), taxonomy);
    }

    @Override
    public boolean canEditTerms(SecurityIdentity identity, String taxonomy) {
        return taxonomyCan(identity, taxonomy, TaxonomyCapabilities::editTerms);
    }

    @Override
    public boolean canAssign(String taxonomy) {
        return canAssign(acting.identity(), taxonomy);
    }

    @Override
    public boolean canAssign(SecurityIdentity identity, String taxonomy) {
        return taxonomyCan(identity, taxonomy, TaxonomyCapabilities::assignTerms);
    }

    private boolean taxonomyCan(
            SecurityIdentity identity, String taxonomy, Function<TaxonomyCapabilities, String> name) {
        Objects.requireNonNull(taxonomy, "taxonomy");
        return taxonomies
                .capabilities(taxonomy)
                .map(name)
                .map(capability -> capabilities.can(identity, capability, Optional.empty()))
                .orElse(false);
    }

    private boolean termCan(SecurityIdentity identity, String metaCapability, TermId term) {
        Objects.requireNonNull(term, "term");
        return terms.findTerm(site.siteId(), term)
                .map(row -> capabilities.can(identity, metaCapability, Optional.of(termFacts.of(row.toSnapshot()))))
                .orElse(false);
    }

    @Override
    public PostTermsAccess postTermsAccess(PostId post, String taxonomy) {
        return postTermsAccess(acting.identity(), post, taxonomy);
    }

    @Override
    @Transactional
    public PostTermsAccess postTermsAccess(SecurityIdentity identity, PostId post, String taxonomy) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(taxonomy, "taxonomy");
        Optional<PostEntity> row = posts.findPost(site.siteId(), post);
        if (row.isEmpty()) {
            return PostTermsAccess.MISSING_POST;
        }
        String type = row.get().type;
        boolean used =
                taxonomies.find(taxonomy).map(found -> found.attachedTo(type)).orElse(false);
        if (!used) {
            return PostTermsAccess.DENIED;
        }
        PostFacts facts = postFacts.of(row.get().toSnapshot());
        if (publiclyViewable(type, facts.effectiveStatus())
                || capabilities.can(identity, "read_post", Optional.of(facts))) {
            return PostTermsAccess.ALLOWED;
        }
        return PostTermsAccess.DENIED;
    }

    /** WordPress's publicly viewable post: a viewable type and a viewable status. */
    private boolean publiclyViewable(String type, String status) {
        return types.find(type).map(PostPermissions::typeViewable).orElse(false)
                && statuses.find(status).map(PostPermissions::statusViewable).orElse(false);
    }
}
