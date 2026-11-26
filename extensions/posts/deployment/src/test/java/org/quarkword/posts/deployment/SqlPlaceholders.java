package org.quarkword.posts.deployment;

import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Records the most bind placeholders and the longest text of the statements QuarkWord's unit sent since a reset
 * (shared by the test and the bean Hibernate calls).
 */
@PersistenceUnitExtension("quarkword")
@ApplicationScoped
public class SqlPlaceholders implements StatementInspector {

    private static final AtomicInteger MOST = new AtomicInteger();
    private static final AtomicInteger LONGEST = new AtomicInteger();

    @Override
    public String inspect(String sql) {
        int placeholders = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                placeholders++;
            }
        }
        MOST.accumulateAndGet(placeholders, Math::max);
        LONGEST.accumulateAndGet(sql.length(), Math::max);
        return sql;
    }

    public static void reset() {
        MOST.set(0);
        LONGEST.set(0);
    }

    /** The most placeholders one statement had. */
    public static int most() {
        return MOST.get();
    }

    /** The length of the longest statement. */
    public static int longest() {
        return LONGEST.get();
    }
}
