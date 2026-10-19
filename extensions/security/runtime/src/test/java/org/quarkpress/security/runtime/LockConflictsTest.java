package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.PessimisticLockException;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

class LockConflictsTest {

    private static RuntimeException wrapped(SQLException cause) {
        return new IllegalStateException("flush failed", new RuntimeException("statement failed", cause));
    }

    @Test
    void deadlocksAndLockTimeoutsOfBothDatabasesAreRetryable() {
        List<SQLException> conflicts = List.of(
                new SQLException("deadlock detected", "40P01"),
                new SQLException("canceling statement due to lock timeout", "55P03"),
                new SQLException("Deadlock found when trying to get lock", "40001", 1213),
                new SQLException("Lock wait timeout exceeded", "HY000", 1205));
        for (SQLException conflict : conflicts) {
            RuntimeException failure = wrapped(conflict);
            assertThat(LockConflicts.isDeadlockOrLockTimeout(failure))
                    .as(conflict.getMessage())
                    .isTrue();
            RuntimeException classified = LockConflicts.retryable(failure, "a membership change of user 5 on site 7");
            assertThat(classified)
                    .as(conflict.getMessage())
                    .isInstanceOf(PessimisticLockException.class)
                    .hasMessage("a membership change of user 5 on site 7 met a concurrent change (a deadlock or a lock"
                            + " timeout) and its transaction must be retried")
                    .hasCause(failure);
        }
    }

    @Test
    void otherFailuresStayAsTheyAre() {
        for (SQLException other : List.of(
                new SQLException("duplicate key", "23505"),
                new SQLException("Duplicate entry", "23000", 1062),
                new SQLException("foreign key", "23503"))) {
            RuntimeException failure = wrapped(other);
            assertThat(LockConflicts.isDeadlockOrLockTimeout(failure)).isFalse();
            assertThat(LockConflicts.retryable(failure, "x")).isSameAs(failure);
        }
        IllegalArgumentException plain = new IllegalArgumentException("user 5 does not exist");
        assertThat(LockConflicts.retryable(plain, "x")).isSameAs(plain);
    }
}
