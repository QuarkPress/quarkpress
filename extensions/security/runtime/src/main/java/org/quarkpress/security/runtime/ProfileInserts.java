package org.quarkpress.security.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hibernate.Session;
import org.quarkpress.persistence.runtime.Dialects;

/**
 * Inserts a profile only if neither its subject nor its nicename exists yet, in one statement, so a concurrent first
 * login never surfaces as a unique-key failure (which would abort the transaction on PostgreSQL). PostgreSQL uses
 * {@code on conflict do nothing}; MySQL uses {@code on duplicate key update id = id} (an exclusive lock on the existing
 * row, never a shared one) and reads whether it inserted from {@code LAST_INSERT_ID()}, reset to 0 first.
 */
@ApplicationScoped
public class ProfileInserts {

    private static final String POSTGRESQL = "insert into qp_user_profile"
            + " (subject, display_name, nicename, meta, created_at, last_login_at)"
            + " values (?1, ?2, ?3, cast('{}' as json), ?4, ?4) on conflict do nothing returning id";
    private static final String MYSQL = "insert into qp_user_profile"
            + " (subject, display_name, nicename, meta, created_at, last_login_at)"
            + " values (?1, ?2, ?3, '{}', ?4, ?4) on duplicate key update id = id";
    private static final String MYSQL_RESET = "select last_insert_id(0)";
    private static final String MYSQL_INSERTED = "select last_insert_id()";
    private static final String LOCK_SUBJECT = "select id from qp_user_profile where subject = ?1 for share";

    @Inject
    UserProfileRepository profiles;

    /** Inserts the profile unless its subject or nicename exists; returns the new id, or empty. */
    public Optional<Long> insertIfAbsent(String subject, String displayName, String nicename, Instant now) {
        Session session = profiles.getSession();
        if (Dialects.postgresql(session)) {
            List<?> ids = session.createNativeQuery(POSTGRESQL, Object.class)
                    .setParameter(1, subject)
                    .setParameter(2, displayName)
                    .setParameter(3, nicename)
                    .setParameter(4, now, Instant.class)
                    .getResultList();
            return ids.isEmpty() ? Optional.empty() : Optional.of(((Number) ids.get(0)).longValue());
        }
        session.createNativeQuery(MYSQL_RESET, Long.class).getSingleResult();
        session.createNativeMutationQuery(MYSQL)
                .setParameter(1, subject)
                .setParameter(2, displayName)
                .setParameter(3, nicename)
                .setParameter(4, now, Instant.class)
                .executeUpdate();
        long id = session.createNativeQuery(MYSQL_INSERTED, Long.class).getSingleResult();
        return id > 0 ? Optional.of(id) : Optional.empty();
    }

    /**
     * The id of the subject's profile, read with a shared lock, which sees a profile a concurrent transaction committed
     * after this transaction's snapshot was taken (MySQL's REPEATABLE READ).
     */
    public Optional<Long> lockedIdOfSubject(String subject) {
        List<?> ids = profiles.getSession()
                .createNativeQuery(LOCK_SUBJECT, Object.class)
                .setParameter(1, subject)
                .getResultList();
        return ids.isEmpty() ? Optional.empty() : Optional.of(((Number) ids.get(0)).longValue());
    }
}
