package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * SmallRye Config does not trim the elements of a list, so {@code a, b} gives {@code " b"}: the bootstrap trims each
 * subject and skips the ones left empty (a padded {@code sub} could never log in anyway).
 */
class BootstrapTrimmedSubjectsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(BootstrapTrimmedSubjectsDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.security.initial-super-admins", "boot-trim-a, boot-trim-b , ");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private long adminRows(String subject) {
        return Jdbc.count(
                dataSource,
                "select count(*) from qp_network_admin a join qp_user_profile p on p.id = a.user_id"
                        + " where p.subject = ?",
                subject);
    }

    @Test
    void subjectsAreTrimmedAndEmptyOnesSkipped() {
        assertThat(adminRows("boot-trim-a")).isEqualTo(1);
        assertThat(adminRows("boot-trim-b")).isEqualTo(1);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_user_profile where subject like '%boot-trim%'"
                                + " and subject not in ('boot-trim-a', 'boot-trim-b')"))
                .isZero();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_user_profile where subject = ''"))
                .isZero();
    }
}
