package org.quarkword.posts.runtime;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkword.core.SiteContext;
import org.quarkword.core.UserId;
import org.quarkword.persistence.runtime.TenantIds;
import org.quarkword.posts.PostAuthors;
import org.quarkword.posts.PostTypes;
import org.quarkword.security.Capabilities;
import org.quarkword.security.PostTypeCapabilities;
import org.quarkword.security.QuarkWordPrincipal;

/** Questions about an author's posts, answered with one existence query on the bound site. */
@ApplicationScoped
public class JpaPostAuthors implements PostAuthors {

    @Inject
    PostRepository posts;

    @Inject
    PostTypes types;

    @Inject
    Capabilities capabilities;

    @Inject
    SiteContext site;

    @Inject
    Acting acting;

    @Override
    public boolean hasPostsVisibleToCaller(UserId author, Collection<String> types) {
        return hasPostsVisibleToCaller(acting.identity(), author, types);
    }

    @Override
    @Transactional
    public boolean hasPostsVisibleToCaller(SecurityIdentity identity, UserId author, Collection<String> types) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(types, "types");
        List<String> registered = new ArrayList<>();
        List<String> privateReadable = new ArrayList<>();
        for (String type : new LinkedHashSet<>(types)) {
            Optional<PostTypeCapabilities> names = this.types.capabilities(type);
            if (names.isPresent()) {
                registered.add(type);
                if (capabilities.can(identity, names.get().readPrivatePosts(), Optional.empty())) {
                    privateReadable.add(type);
                }
            }
        }
        if (registered.isEmpty()) {
            return false;
        }
        Optional<UserId> caller = QuarkWordPrincipal.of(identity).map(QuarkWordPrincipal::user);
        StringBuilder text = new StringBuilder("select p.id from PostEntity p where p.siteId = :site"
                + " and p.authorId = :author and p.type in :types and (p.status = 'publish'");
        List<String> privateRules = new ArrayList<>();
        if (caller.isPresent()) {
            privateRules.add(":caller = p.authorId");
        }
        if (!privateReadable.isEmpty()) {
            privateRules.add("p.type in :privateReadable");
        }
        if (!privateRules.isEmpty()) {
            text.append(" or (p.status = 'private' and (")
                    .append(String.join(" or ", privateRules))
                    .append("))");
        }
        text.append(')');
        TypedQuery<Long> query = posts.getEntityManager()
                .createQuery(text.toString(), Long.class)
                .setParameter("site", TenantIds.param(site.siteId()))
                .setParameter("author", author.value())
                .setParameter("types", registered)
                .setMaxResults(1);
        caller.ifPresent(user -> query.setParameter("caller", user.value()));
        if (!privateReadable.isEmpty()) {
            query.setParameter("privateReadable", privateReadable);
        }
        return !query.getResultList().isEmpty();
    }
}
