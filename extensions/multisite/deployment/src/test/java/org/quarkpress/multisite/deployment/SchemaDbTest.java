package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class SchemaDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(SchemaDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private long newSite() {
        String domain = "schema-" + UUID.randomUUID() + ".test";
        Jdbc.update(dataSource, "insert into qp_site (domain, path, name) values (?, '/', 'schema')", domain);
        return Jdbc.count(dataSource, "select id from qp_site where domain = ?", domain);
    }

    @Test
    void theMultisiteBandRunsAfterThePersistenceBand() {
        assertThat(Jdbc.strings(
                        dataSource,
                        "select script from qp_schema_history where success = true and type = 'SQL'"
                                + " order by installed_rank"))
                .containsSubsequence(
                        "db/quarkpress/" + TestDb.kind() + "/V1_001__persistence_create_tables.sql",
                        "db/quarkpress/" + TestDb.kind() + "/V2_001__multisite_site_domain.sql");
    }

    @Test
    void anAliasBelongsToOneSiteAndGoesWithIt() {
        long site = newSite();
        String alias = "alias-" + UUID.randomUUID() + ".test";
        Jdbc.update(dataSource, "insert into qp_site_domain (site_id, domain) values (?, ?)", site, alias);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_site_domain where domain = ? and redirect_to_primary = ?",
                        alias,
                        true))
                .isEqualTo(1);
        assertThatThrownBy(() -> Jdbc.update(
                        dataSource, "insert into qp_site_domain (site_id, domain) values (?, ?)", site, alias))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_site_domain_domain_uq"));
        Jdbc.update(dataSource, "delete from qp_site where id = ?", site);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_site_domain where domain = ?", alias))
                .isZero();
    }
}
