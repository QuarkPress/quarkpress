package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * WordPress's error page: its robots meta follows the site's {@code blog_public} option alone (WordPress's die handler
 * adds the no-robots rule to the default ones), whatever the status. The strings are those PHP prints for a public and
 * for a private site.
 */
class WpDieTest {

    private static final String PUBLIC = "<meta name='robots' content='max-image-preview:large, noindex, follow' />";
    private static final String PRIVATE = "<meta name='robots' content='noindex, nofollow' />";

    private static String page(int status, boolean publicSite) {
        return new String(WpDie.died(status, "Message.", null, null, publicSite).body(), StandardCharsets.UTF_8);
    }

    @Test
    void aPublicSiteIsFollowedWhateverTheStatus() {
        for (int status : new int[] {403, 410, 500}) {
            assertThat(page(status, true)).contains(PUBLIC).doesNotContain(PRIVATE);
        }
    }

    @Test
    void aPrivateSiteIsNotFollowedWhateverTheStatus() {
        for (int status : new int[] {403, 410, 500}) {
            assertThat(page(status, false)).contains(PRIVATE).doesNotContain(PUBLIC);
        }
    }
}
