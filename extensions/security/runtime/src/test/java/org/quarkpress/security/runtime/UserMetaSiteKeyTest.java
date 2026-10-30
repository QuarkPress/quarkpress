package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quarkpress.core.SiteId;

class UserMetaSiteKeyTest {

    @Test
    void siteOneHasTheBarePrefixAndEveryOtherSiteItsId() {
        // as WordPress prefixes by site id, whichever site is the main one
        assertThat(JpaUserMeta.siteKeyOf(SiteId.of(1), "persisted_preferences")).isEqualTo("wp_persisted_preferences");
        assertThat(JpaUserMeta.siteKeyOf(SiteId.of(2), "persisted_preferences"))
                .isEqualTo("wp_2_persisted_preferences");
        assertThat(JpaUserMeta.siteKeyOf(SiteId.of(10), "persisted_preferences"))
                .isEqualTo("wp_10_persisted_preferences");
        assertThat(JpaUserMeta.siteKeyOf(SiteId.of(11), "x")).isEqualTo("wp_11_x");
    }
}
