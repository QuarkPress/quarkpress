package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The isolation level QuarkPress gives its datasource is a default the application can change. */
class IsolationLevelOverrideDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(IsolationLevelOverrideDbTest.class)
            .overrideConfigKey("quarkus.datasource.\"quarkpress\".jdbc.transaction-isolation-level", "serializable");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void theApplicationCanChooseAnotherLevel() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        }
    }
}
