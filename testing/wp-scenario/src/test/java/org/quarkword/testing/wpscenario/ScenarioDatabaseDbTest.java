package org.quarkword.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class ScenarioDatabaseDbTest {

    @Test
    void aMySqlServerStartsForPhpAndJdbc() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        try (ScenarioDatabase database = ScenarioDatabase.start();
                Connection connection = database.connect("wp_scenario");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select version()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).startsWith("9.5");
            assertThat(database.host()).isNotEqualTo("localhost");
            assertThat(database.environment())
                    .containsEntry("QW_DB_USER", "root")
                    .containsEntry("QW_DB_PORT", Integer.toString(database.port()))
                    .containsKeys("QW_DB_HOST", "QW_DB_PASSWORD");
        }
    }
}
