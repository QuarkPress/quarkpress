package org.quarkpress.posts.runtime;

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
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PostAuthors;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.QuarkPressPrincipal;

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
        Optional<UserId> caller = QuarkPressPrincipal.of(identity).map(QuarkPressPrincipal::user);
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
