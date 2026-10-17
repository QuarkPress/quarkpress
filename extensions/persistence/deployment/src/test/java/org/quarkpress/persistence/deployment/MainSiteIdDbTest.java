package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;

/** The run-time main site id sits under the same prefix as the build-time settings. */
class MainSiteIdDbTest {

    /** Far above the ids the identity columns hand out, so no test site collides with it. */
    static final long MAIN_SITE = 900_000_007L;

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(MainSiteIdDbTest.class, MainSiteSeed.class)
            .overrideRuntimeConfigKey("quarkpress.main-site-id", Long.toString(MAIN_SITE));

    /** Creates the main site after the migrations and before QuarkPress checks that it exists. */
    @ApplicationScoped
    public static class MainSiteSeed {

        @Inject
        @DataSource("quarkpress")
        AgroalDataSource dataSource;

        void seed(@Observes @Priority(1) StartupEvent startup) {
            if (Jdbc.count(dataSource, "select count(*) from qp_site where id = ?", MAIN_SITE) == 0) {
                Jdbc.update(
                        dataSource,
                        "insert into qp_site (id, domain, path, name) values (?, ?, '/', 'Main')",
                        MAIN_SITE,
                        "main-" + MAIN_SITE + ".test");
            }
        }
    }

    @Inject
    SiteContext context;

    @Test
    void theConfiguredMainSiteIsCurrent() {
        Requests.fresh(() -> {
            assertThat(context.siteId()).isEqualTo(SiteId.of(MAIN_SITE));
            assertThat(context.site().domain()).isEqualTo("main-" + MAIN_SITE + ".test");
        });
    }
}
