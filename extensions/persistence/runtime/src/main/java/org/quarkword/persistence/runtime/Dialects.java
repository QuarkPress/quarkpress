package org.quarkword.persistence.runtime;

import org.hibernate.Session;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;

/** The SQL dialect of a session of QuarkWord's persistence unit, for native statements that differ by database. */
public final class Dialects {

    private Dialects() {}

    /** Whether the session talks to PostgreSQL (otherwise MySQL; PostgreSQL and MySQL are the databases QuarkWord supports). */
    public static boolean postgresql(Session session) {
        return session.getSessionFactory()
                        .unwrap(SessionFactoryImplementor.class)
                        .getJdbcServices()
                        .getDialect()
                instanceof PostgreSQLDialect;
    }
}
