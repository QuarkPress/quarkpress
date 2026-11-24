package org.quarkword.multisite.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkword.core.SiteId;
import org.quarkword.core.runtime.Binding;

/** The site other modules read under {@link SiteRouteFilter#SITE_KEY}. */
class RoutedSiteTest {

    private static final SiteId SITE = SiteId.of(7);

    @Test
    void aSiteBindingWithoutARedirectHasItsSite() {
        assertThat(new RoutedSite(Binding.site(SITE), Optional.empty()).site()).contains(SITE);
    }

    @Test
    void aRedirectingAliasHasNoSite() {
        URI target = URI.create("https://example.com/");
        assertThat(new RoutedSite(Binding.site(SITE), Optional.of(target)).site())
                .isEmpty();
    }

    @Test
    void networkAndUnresolvedBindingsHaveNoSite() {
        assertThat(new RoutedSite(Binding.NETWORK, Optional.empty()).site()).isEmpty();
        assertThat(new RoutedSite(Binding.UNRESOLVED, Optional.empty()).site()).isEmpty();
    }
}
