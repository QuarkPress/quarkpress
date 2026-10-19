package org.quarkpress.persistence.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.hibernate.Session;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.SiteScopedNativeSql;

/**
 * Inserts an option only if it does not exist yet, in one statement: a writer that loses a race against a concurrent
 * insert of the same name gets false instead of a unique-key failure (which would abort the transaction on
 * PostgreSQL). PostgreSQL uses {@code on conflict do nothing}, which takes no lock on the existing row. MySQL uses
 * {@code on duplicate key update id = id}, which takes an exclusive lock on the existing row (an {@code insert ignore}
 * would take a shared one, and two losers upgrading their shared locks deadlock); whether the statement inserted is
 * read from {@code LAST_INSERT_ID()}, reset to 0 first on the same connection, because MySQL's affected-row count
 * reports a found duplicate as 1. Other errors (a missing site, a value too long) are not downgraded.
 *
 * <p>The update clause {@code id = id} must stay a no-op: MySQL sets {@code LAST_INSERT_ID()} when a duplicate is
 * updated and the row changes, so a clause that changed a column (for example {@code autoload = values(autoload)})
 * would report an existing option as inserted.
 *
 * <p>Native SQL bypasses the site filter and listener: every statement names its site explicitly, and the request
 * must be bound to that site or to the network.
 */
@SiteScopedNativeSql
@ApplicationScoped
public class OptionInserts {

    private static final String POSTGRESQL = "insert into qp_option (site_id, name, value, autoload, updated_at)"
            + " values (?1, ?2, cast(?3 as json), ?4, ?5) on conflict (site_id, name) do nothing";
    private static final String MYSQL = "insert into qp_option (site_id, name, value, autoload, updated_at)"
            + " values (?1, ?2, ?3, ?4, ?5) on duplicate key update id = id";
    private static final String MYSQL_RESET = "select last_insert_id(0)";
    private static final String MYSQL_INSERTED = "select last_insert_id()";

    @Inject
    OptionRepository options;

    /** Inserts the site option unless it exists; returns whether this call inserted it. */
    public boolean insertIfAbsent(SiteId site, String name, JsonNode stored, boolean autoload, Instant now) {
        OptionWrites.requireWritable(site);
        Session session = options.getSession();
        if (Dialects.postgresql(session)) {
            return session.createNativeMutationQuery(POSTGRESQL)
                            .setParameter(1, TenantIds.param(site))
                            .setParameter(2, StoredOptionNames.require(name))
                            .setParameter(3, QuarkPressJsonFormatMapper.write(stored))
                            .setParameter(4, autoload)
                            .setParameter(5, now, Instant.class)
                            .executeUpdate()
                    == 1;
        }
        session.createNativeQuery(MYSQL_RESET, Long.class).getSingleResult();
        session.createNativeMutationQuery(MYSQL)
                .setParameter(1, TenantIds.param(site))
                .setParameter(2, StoredOptionNames.require(name))
                .setParameter(3, QuarkPressJsonFormatMapper.write(stored))
                .setParameter(4, autoload)
                .setParameter(5, now, Instant.class)
                .executeUpdate();
        return session.createNativeQuery(MYSQL_INSERTED, Long.class).getSingleResult() > 0;
    }
}
