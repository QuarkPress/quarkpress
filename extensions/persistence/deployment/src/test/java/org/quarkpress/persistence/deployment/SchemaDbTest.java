package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteStatus;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.runtime.NetworkOptionRepository;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.SiteEntity;
import org.quarkpress.persistence.runtime.SiteRepository;

class SchemaDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(SchemaDbTest.class);

    private static final Instant AT = Instant.parse("2026-10-17T12:34:56.123456Z");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    SiteRepository sites;

    @Inject
    OptionRepository options;

    @Inject
    NetworkOptionRepository networkOptions;

    @Inject
    SiteBinding binding;

    @Inject
    TestSites testSites;

    @Test
    void runsOnTheSelectedDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName())
                    .isEqualTo(TestDb.kind().equals("mysql") ? "MySQL" : "PostgreSQL");
        }
    }

    @Test
    void appliesTheFirstBandOfMigrations() {
        assertThat(Jdbc.strings(
                        dataSource,
                        "select script from qp_schema_history where success = true and type = 'SQL'"
                                + " order by installed_rank"))
                .containsExactly(
                        "db/quarkpress/" + TestDb.kind() + "/V1_001__persistence_create_tables.sql",
                        "db/quarkpress/common/V1_002__persistence_main_site.sql",
                        "db/quarkpress/common/V1_003__persistence_network_defaults.sql",
                        "db/quarkpress/" + TestDb.kind() + "/V1_004__persistence_network_lock.sql",
                        "db/quarkpress/" + TestDb.kind() + "/V1_005__persistence_site_defaults.sql");
    }

    @Test
    void theNetworkLockHasOneRow() {
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_network_lock"))
                .isEqualTo(1);
        assertThat(Jdbc.count(dataSource, "select id from qp_network_lock")).isEqualTo(1);
    }

    @Test
    void createsTheMainSite() {
        Requests.fresh(() -> QuarkusTransaction.requiringNew().run(() -> {
            SiteEntity main = sites.findById(1L);
            assertThat(main.domain).isEqualTo("localhost");
            assertThat(main.path).isEqualTo("/");
            assertThat(main.name).isEqualTo("QuarkPress");
            assertThat(main.publicFlag).isEqualTo((short) 1);
            assertThat(main.toInfo().status()).isEqualTo(SiteStatus.ACTIVE);
            assertThat(main.registeredAt).isNotNull();
            assertThat(main.version).isZero();
        }));
    }

    @Test
    void storesEverySiteField() {
        long id = Requests.call(() -> QuarkusTransaction.requiringNew().call(() -> {
            SiteEntity site = SiteEntity.create("fields-" + UUID.randomUUID() + ".test", "/blog/", "Fields", AT);
            site.publicFlag = -1;
            site.archived = true;
            site.mature = true;
            site.spam = true;
            site.deleted = true;
            sites.persist(site);
            return site.id;
        }));
        Requests.fresh(() -> QuarkusTransaction.requiringNew().run(() -> {
            SiteEntity site = sites.findById(id);
            assertThat(site.path).isEqualTo("/blog/");
            assertThat(site.publicFlag).isEqualTo((short) -1);
            assertThat(site.archived && site.mature && site.spam && site.deleted)
                    .isTrue();
            assertThat(site.registeredAt).isEqualTo(AT);
            assertThat(site.updatedAt).isEqualTo(AT);
            site.name = "Renamed";
        }));
        Requests.fresh(() -> QuarkusTransaction.requiringNew().run(() -> {
            SiteEntity site = sites.findById(id);
            assertThat(site.name).isEqualTo("Renamed");
            assertThat(site.version).isEqualTo(1);
        }));
    }

    @Test
    void storesOptionsAsJsonForTheBoundSite() {
        SiteId site = testSites.create("json");
        JsonNode value = Json.parse("{\"list\":[1,\"two\",true,{\"big\":9007199254740993}],\"text\":\"caf\\u00e9\"}");
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            QuarkusTransaction.requiringNew()
                    .run(() -> options.persist(OptionEntity.create(site, "json_test", value, true, AT)));
        });
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            QuarkusTransaction.requiringNew().run(() -> {
                OptionEntity stored = options.findOption(site, "json_test").orElseThrow();
                assertThat(stored.value).isEqualTo(value);
                assertThat(stored.site()).isEqualTo(site);
                assertThat(stored.autoload).isTrue();
                assertThat(stored.updatedAt).isEqualTo(AT);
            });
        });
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_option where site_id = ? and name = ?",
                        site.value(),
                        "json_test"))
                .isEqualTo(1);
    }

    @Test
    void aJsonNullValueReadsBackAsNullNode() {
        SiteId site = testSites.create("null");
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            QuarkusTransaction.requiringNew()
                    .run(() ->
                            options.persist(OptionEntity.create(site, "null_test", NullNode.getInstance(), true, AT)));
        });
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            QuarkusTransaction.requiringNew()
                    .run(() -> assertThat(options.findOption(site, "null_test").orElseThrow().value)
                            .isEqualTo(NullNode.getInstance()));
        });
    }

    @Test
    void optionNamesAreUniquePerSite() {
        SiteId site = testSites.create("unique");
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            QuarkusTransaction.requiringNew()
                    .run(() -> options.persist(OptionEntity.create(site, "dup", TextNode.valueOf("a"), true, AT)));
            assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
                        options.persist(OptionEntity.create(site, "dup", TextNode.valueOf("b"), true, AT));
                        options.flush();
                    }))
                    .satisfies(
                            failure -> assertThat(Failures.messages(failure)).contains("ConstraintViolationException"));
        });
    }

    @Test
    void theNetworkFeatureDefaultsStartEmpty() {
        Requests.fresh(() -> QuarkusTransaction.requiringNew()
                .run(() -> assertThat(networkOptions.findById("qp_feature_defaults").value)
                        .isEqualTo(Json.parse("{}"))));
    }

    @Test
    void deletingASiteDeletesItsOptions() {
        SiteId site = testSites.create("cascade");
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            QuarkusTransaction.requiringNew()
                    .run(() -> options.persist(OptionEntity.create(site, "gone", TextNode.valueOf("x"), true, AT)));
        });
        Requests.fresh(() -> QuarkusTransaction.requiringNew().run(() -> sites.deleteById(site.value())));
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_option where site_id = ?", site.value()))
                .isZero();
    }
}
