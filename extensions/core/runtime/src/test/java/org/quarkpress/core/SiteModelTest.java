package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SiteModelTest {

    @Test
    void noneIsTheEmptyRequest() {
        assertThat(SiteRequest.none().isNone()).isTrue();
        assertThat(new SiteRequest("https", "example.test", 443, "/").isNone()).isFalse();
    }

    @Test
    void requestRejectsBadPortsAndNulls() {
        assertThatThrownBy(() -> new SiteRequest("https", "a.test", 65536, "/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SiteRequest("https", "a.test", -2, "/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SiteRequest(null, "a.test", 80, "/")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void requestRejectsNullHostAndPath() {
        assertThatThrownBy(() -> new SiteRequest("https", null, 80, "/")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SiteRequest("https", "a.test", 80, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void portBoundariesAreInclusive() {
        assertThat(new SiteRequest("https", "a.test", -1, "/").port()).isEqualTo(-1);
        assertThat(new SiteRequest("https", "a.test", 65535, "/").port()).isEqualTo(65535);
        assertThat(new SiteRequest("https", "a.test", 0, "/").port()).isZero();
    }

    @Test
    void statusFollowsTheStrongestFlag() {
        assertThat(info(false, false, false).status()).isEqualTo(SiteStatus.ACTIVE);
        assertThat(info(true, false, false).status()).isEqualTo(SiteStatus.ARCHIVED);
        assertThat(info(true, true, false).status()).isEqualTo(SiteStatus.SPAM);
        assertThat(info(true, true, true).status()).isEqualTo(SiteStatus.DELETED);
    }

    @Test
    void matureDoesNotChangeTheStatus() {
        SiteInfo mature = new SiteInfo(SiteId.of(1), "a.test", "/", "A", 1, false, true, false, false);
        assertThat(mature.status()).isEqualTo(SiteStatus.ACTIVE);
    }

    private static SiteInfo info(boolean archived, boolean spam, boolean deleted) {
        return new SiteInfo(SiteId.of(1), "a.test", "/", "A", 1, archived, false, spam, deleted);
    }
}
