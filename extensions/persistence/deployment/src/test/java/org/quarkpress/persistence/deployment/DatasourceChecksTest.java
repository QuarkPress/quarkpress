package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatasourceChecksTest {

    @Test
    void theMissingDatasourceMessageListsEveryProperty() {
        assertThat(DatasourceChecks.missingDatasource())
                .contains("quarkus.datasource.\"quarkpress\".db-kind")
                .contains("quarkus.datasource.\"quarkpress\".jdbc.url")
                .contains("quarkus.datasource.\"quarkpress\".username")
                .contains("quarkus.datasource.\"quarkpress\".password");
    }

    @Test
    void postgresqlAndMysqlAreSupported() {
        assertThat(DatasourceChecks.vendorProblem("postgresql", "postgresql")).isEmpty();
        assertThat(DatasourceChecks.vendorProblem("mysql", "mysql")).isEmpty();
        assertThat(DatasourceChecks.vendorProblem("mariadb", "mysql")).isEmpty();
    }

    @Test
    void mariadbIsToldToUseTheMysqlMigrations() {
        assertThat(DatasourceChecks.vendorProblem("mariadb", "mariadb"))
                .hasValueSatisfying(
                        message -> assertThat(message).contains("quarkpress.persistence.migration-vendor=mysql"));
    }

    @Test
    void otherVendorsAreRejected() {
        assertThat(DatasourceChecks.vendorProblem("postgres", "postgres"))
                .hasValueSatisfying(
                        message -> assertThat(message).contains("'postgres'").contains("postgresql or mysql"));
        assertThat(DatasourceChecks.vendorProblem("oracle", "oracle")).isPresent();
    }
}
