package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.FeatureRegistry;
import org.quarkpress.restwp.RestWpV2Feature;
import org.quarkpress.restwp.runtime.RestWpConfig;

class ExtensionDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.with(ExtensionDbTest.class)
            .setLogRecordPredicate(
                    record -> record.getMessage() != null && record.getMessage().startsWith("Installed features"))
            .assertLogRecords(records -> assertThat(records)
                    .as("the startup line that lists the installed features")
                    .anySatisfy(record ->
                            assertThat(Arrays.toString(record.getParameters())).contains("quarkpress-rest-wp-v2")));

    @Inject
    FeatureRegistry features;

    @Inject
    RestWpConfig config;

    @Test
    void theSiteFeatureIsDeclaredOnByDefault() {
        FeatureDescriptor feature = features.find("rest-wp-v2").orElseThrow();
        assertThat(feature.key()).isEqualTo(RestWpV2Feature.KEY);
        assertThat(feature.module()).isEqualTo("rest-wp-v2");
        assertThat(feature.defaultEnabled()).isTrue();
        assertThat(feature.description())
                .isEqualTo("Serves WordPress's REST API (wp/v2) for the site; when off, every REST URL of the site"
                        + " answers 404 rest_no_route");
    }

    @Test
    void theConfigurationDefaultsToWpJsonWithoutCorsOrigins() {
        assertThat(config.path()).isEqualTo("/wp-json");
        assertThat(config.cors().origins()).isEmpty();
    }
}
