package org.quarkword.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteRequest;
import org.quarkword.core.runtime.SiteResolution;
import org.quarkword.multisite.runtime.MultisiteSettingsView;
import org.quarkword.multisite.runtime.ResolverStrategy;
import org.quarkword.persistence.runtime.MainSiteCheck;
import org.quarkword.persistence.runtime.SingleSiteResolver;

class ConfigDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(ConfigDbTest.class)
            .overrideRuntimeConfigKey("quarkword.multisite.resolvers", "path,domain")
            .overrideRuntimeConfigKey("quarkword.multisite.path-segments", "2");

    @Inject
    MultisiteSettingsView settings;

    @Inject
    SiteResolution resolution;

    @Test
    void theSettingsAreRead() {
        assertThat(settings.resolvers()).containsExactlyInAnyOrder(ResolverStrategy.PATH, ResolverStrategy.DOMAIN);
        assertThat(settings.pathSegments()).isEqualTo(2);
    }

    @Test
    void theSingleSiteBeansAreGone() {
        assertThat(Arc.container().select(SingleSiteResolver.class).isResolvable())
                .isFalse();
        assertThat(Arc.container().select(MainSiteCheck.class).isResolvable()).isFalse();
    }

    @Test
    void workWithoutARequestBelongsToNoSite() {
        assertThat(resolution.resolve(SiteRequest.none())).isEmpty();
    }
}
