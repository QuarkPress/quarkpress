package org.quarkword.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** QuarkWord's connections read committed data on both databases. */
class IsolationLevelDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(IsolationLevelDbTest.class);

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Test
    void connectionsReadCommittedData() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        }
    }
}
