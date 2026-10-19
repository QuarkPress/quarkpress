package org.quarkpress.security.runtime;

import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.hibernate.Session;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.persistence.runtime.Dialects;

/**
 * Creates a membership row (no roles, no capabilities) only if it does not exist yet, in one statement, so concurrent
 * first changes of one membership never fail on its unique key. The request must be bound to the site or the network,
 * as the listener requires for site rows: native SQL is not seen by it.
 */
@SiteScopedNativeSql
@ApplicationScoped
public class MembershipInserts {

    private static final String POSTGRESQL = "insert into qp_site_membership (site_id, user_id, roles, capabilities,"
            + " created_at) values (?1, ?2, cast('[]' as json), cast('{}' as json), ?3)"
            + " on conflict (site_id, user_id) do nothing";
    private static final String MYSQL = "insert into qp_site_membership (site_id, user_id, roles, capabilities,"
            + " created_at) values (?1, ?2, '[]', '{}', ?3) on duplicate key update id = id";

    @Inject
    SiteMembershipRepository memberships;

    public void ensure(SiteId site, UserId user, Instant now) {
        Binding bound = Arc.container().instance(SiteBinding.class).get().current();
        if (bound.kind() != Binding.Kind.NETWORK && !bound.equals(Binding.site(site))) {
            throw new IllegalStateException("a membership of site " + site.value() + " written under " + bound);
        }
        Session session = memberships.getSession();
        session.createNativeMutationQuery(Dialects.postgresql(session) ? POSTGRESQL : MYSQL)
                .setParameter(1, site.value())
                .setParameter(2, user.value())
                .setParameter(3, now, Instant.class)
                .executeUpdate();
    }
}
