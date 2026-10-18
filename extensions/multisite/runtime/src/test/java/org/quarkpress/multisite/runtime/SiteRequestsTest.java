package org.quarkpress.multisite.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SiteRequestsTest {

    @Test
    void theTargetIsKeptAsReceived() {
        assertThat(SiteRequests.target("/a/../b?x=%2e")).isEqualTo("/a/../b?x=%2e");
        assertThat(SiteRequests.target("")).isEmpty();
        assertThat(SiteRequests.target(null)).isEmpty();
    }

    @Test
    void anAbsoluteTargetLosesItsAuthority() {
        assertThat(SiteRequests.target("http://example.org/news/x?y=1")).isEqualTo("/news/x?y=1");
        assertThat(SiteRequests.target("http://example.org?y=1")).isEqualTo("/?y=1");
        assertThat(SiteRequests.target("http://example.org")).isEmpty();
        assertThat(SiteRequests.target("http://example.org/a/../b")).isEqualTo("/a/../b");
    }

    @Test
    void otherFormsHaveNoPath() {
        assertThat(SiteRequests.target("*")).isEmpty();
        assertThat(SiteRequests.target("host:443")).isEmpty();
        assertThat(SiteRequests.target("http:/x")).isEmpty();
    }
}
