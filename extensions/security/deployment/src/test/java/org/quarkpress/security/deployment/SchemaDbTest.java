package org.quarkpress.security.deployment;

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
    static final QuarkusExtensionTest app = SecurityApp.with(SchemaDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private long newSite() {
        String domain = "schema-" + UUID.randomUUID() + ".test";
        Jdbc.update(dataSource, "insert into qp_site (domain, path, name) values (?, '/', 'schema')", domain);
        return Jdbc.count(dataSource, "select id from qp_site where domain = ?", domain);
    }

    private long newProfile(String label) {
        String subject = label + "-" + UUID.randomUUID();
        Jdbc.update(
                dataSource,
                "insert into qp_user_profile (subject, display_name, nicename) values (?, ?, ?)",
                subject,
                label,
                subject.substring(0, Math.min(50, subject.length())));
        return Jdbc.count(dataSource, "select id from qp_user_profile where subject = ?", subject);
    }

    @Test
    void theSecurityBandRunsAfterThePersistenceBand() {
        assertThat(Jdbc.strings(
                        dataSource,
                        "select script from qp_schema_history where success = true and type = 'SQL'"
                                + " order by installed_rank"))
                .containsSubsequence(
                        "db/quarkpress/" + TestDb.kind() + "/V1_001__persistence_create_tables.sql",
                        "db/quarkpress/" + TestDb.kind() + "/V3_001__security_create_tables.sql");
    }

    @Test
    void subjectsAndNicenamesAreUnique() {
        String subject = "unique-" + UUID.randomUUID();
        Jdbc.update(
                dataSource,
                "insert into qp_user_profile (subject, display_name, nicename) values (?, 'a', ?)",
                subject,
                "n-" + subject.substring(7, 30));
        assertThatThrownBy(() -> Jdbc.update(
                        dataSource,
                        "insert into qp_user_profile (subject, display_name, nicename) values (?, 'b', ?)",
                        subject,
                        "m-" + subject.substring(7, 30)))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_user_profile_subject_uq"));
        assertThatThrownBy(() -> Jdbc.update(
                        dataSource,
                        "insert into qp_user_profile (subject, display_name, nicename) values (?, 'c', ?)",
                        "other-" + subject,
                        "n-" + subject.substring(7, 30)))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_user_profile_nicename_uq"));
        assertThat(Jdbc.strings(dataSource, "select meta from qp_user_profile where subject = ?", subject))
                .containsExactly("{}");
    }

    @Test
    void aMembershipIsUniquePerSiteAndUserAndGoesWithTheSiteOrTheUser() {
        long site = newSite();
        long user = newProfile("member");
        Jdbc.update(
                dataSource,
                "insert into qp_site_membership (site_id, user_id, roles) values (?, ?, '[\"editor\"]')",
                site,
                user);
        assertThat(Jdbc.strings(
                        dataSource,
                        "select capabilities from qp_site_membership where site_id = ? and user_id = ?",
                        site,
                        user))
                .containsExactly("{}");
        assertThatThrownBy(() -> Jdbc.update(
                        dataSource,
                        "insert into qp_site_membership (site_id, user_id, roles) values (?, ?, '[]')",
                        site,
                        user))
                .satisfies(
                        failure -> assertThat(Failures.messages(failure)).contains("qp_site_membership_site_user_uq"));
        Jdbc.update(dataSource, "delete from qp_site where id = ?", site);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_site_membership where user_id = ?", user))
                .isZero();

        long other = newSite();
        Jdbc.update(
                dataSource,
                "insert into qp_site_membership (site_id, user_id, roles) values (?, ?, '[]')",
                other,
                user);
        Jdbc.update(dataSource, "delete from qp_user_profile where id = ?", user);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_site_membership where site_id = ?", other))
                .isZero();
    }

    @Test
    void aNetworkAdminGoesWithTheUserAndForgetsAGrantorThatIsGone() {
        long admin = newProfile("admin");
        long grantor = newProfile("grantor");
        Jdbc.update(dataSource, "insert into qp_network_admin (user_id, granted_by) values (?, ?)", admin, grantor);
        Jdbc.update(dataSource, "delete from qp_user_profile where id = ?", grantor);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_network_admin where user_id = ? and granted_by is null",
                        admin))
                .isEqualTo(1);
        Jdbc.update(dataSource, "delete from qp_user_profile where id = ?", admin);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_network_admin where user_id = ?", admin))
                .isZero();
    }

    @Test
    void aDeletedPrimarySiteIsForgotten() {
        long site = newSite();
        long user = newProfile("primary");
        Jdbc.update(dataSource, "update qp_user_profile set primary_site_id = ? where id = ?", site, user);
        Jdbc.update(dataSource, "delete from qp_site where id = ?", site);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_user_profile where id = ? and primary_site_id is null",
                        user))
                .isEqualTo(1);
    }

    @Test
    void jsonColumnsRefuseInvalidJsonOnMysqlAndKeepKeyOrder() {
        long site = newSite();
        long user = newProfile("order");
        Jdbc.update(
                dataSource,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (?, ?, '[]', "
                        + TestDb.json() + ")",
                site,
                user,
                "{\"z\":true,\"a\":false}");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select capabilities from qp_site_membership where site_id = ? and user_id = ?",
                        site,
                        user))
                .containsExactly("{\"z\":true,\"a\":false}");
        if (TestDb.kind().equals("mysql")) {
            assertThatThrownBy(() -> Jdbc.update(
                            dataSource, "update qp_site_membership set roles = 'not json' where site_id = ?", site))
                    .satisfies(failure ->
                            assertThat(Failures.messages(failure)).contains("qp_site_membership_roles_json"));
        }
    }
}
