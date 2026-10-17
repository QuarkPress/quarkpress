package org.quarkpress.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.quarkpress.core.SiteId;

class BindingTest {

    @Test
    void aSiteBindingNeedsASite() {
        assertThatThrownBy(() -> new Binding(Binding.Kind.SITE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Binding.site(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void onlyASiteBindingHasASite() {
        assertThatThrownBy(() -> new Binding(Binding.Kind.NETWORK, SiteId.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Binding(Binding.Kind.UNRESOLVED, SiteId.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Binding(null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theConstantsAndFactoryAgree() {
        assertThat(Binding.NETWORK.site()).isNull();
        assertThat(Binding.UNRESOLVED.kind()).isEqualTo(Binding.Kind.UNRESOLVED);
        assertThat(Binding.site(SiteId.of(2))).isEqualTo(new Binding(Binding.Kind.SITE, SiteId.of(2)));
    }
}
