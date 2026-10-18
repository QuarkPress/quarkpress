package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.multisite.runtime.MultisiteSettingsView;
import org.quarkpress.multisite.runtime.ResolverStrategy;

/** Without settings: all strategies, one path segment (the smallest accepted count). */
class SettingsDefaultsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(SettingsDefaultsDbTest.class);

    @Inject
    MultisiteSettingsView settings;

    @Test
    void theDefaultsAreRead() {
        assertThat(settings.pathSegments()).isEqualTo(1);
        assertThat(settings.resolvers())
                .containsExactlyInAnyOrder(ResolverStrategy.DOMAIN, ResolverStrategy.SUBDOMAIN, ResolverStrategy.PATH);
    }
}
