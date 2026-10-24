package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.FeatureRegistry;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.revisions.runtime.RevisionsConfig;

class FeatureDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(FeatureDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    FeatureRegistry features;

    @Inject
    SiteContext context;

    @Inject
    RevisionsConfig config;

    @Test
    void theModuleDeclaresTheRevisionsFeatureOnByDefault() throws Exception {
        FeatureDescriptor revisions = features.find("revisions").orElseThrow();
        assertThat(revisions.module()).isEqualTo("revisions");
        assertThat(revisions.defaultEnabled()).isTrue();
        SiteId site = TestData.site(dataSource);
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> context.features().isEnabled("revisions")))
                .isTrue();
        TestData.featureOff(dataSource, site, "revisions");
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> context.features().isEnabled("revisions")))
                .isFalse();
    }

    @Test
    void keepDefaultsToUnlimited() {
        assertThat(config.keep()).isEqualTo(-1);
    }
}
