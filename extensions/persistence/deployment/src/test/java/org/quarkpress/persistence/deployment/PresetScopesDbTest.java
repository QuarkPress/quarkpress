package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.multisite.runtime.EverywherePresetSite;
import org.quarkpress.persistence.runtime.Scopes;
import org.quarkpress.scheduling.runtime.ScopeProbe;

class PresetScopesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            DbApp.with(PresetScopesDbTest.class, ScopeProbe.class, EverywherePresetSite.class);

    @Inject
    SiteContext context;

    @Inject
    ScopeProbe probe;

    @AfterEach
    void reset() {
        EverywherePresetSite.PRESET.set(null);
    }

    @Test
    void aSiteScopeBindsItsTargetWhateverThePresetSays() {
        EverywherePresetSite.PRESET.set(Binding.site(SiteId.of(1)));
        Requests.fresh(() -> {
            assertThat(context.siteId()).isEqualTo(SiteId.of(1));
            try {
                assertThat(Scopes.within(Binding.site(SiteId.of(2)), () -> context.siteId()))
                        .isEqualTo(SiteId.of(2));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void theNetworkScopeBindsTheNetworkWhateverThePresetSays() {
        EverywherePresetSite.PRESET.set(Binding.site(SiteId.of(1)));
        Requests.fresh(() -> {
            try {
                assertThat(probe.bindingInNetworkScope()).isEqualTo(Binding.NETWORK);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }
}
