package org.quarkpress.lib.wppassword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NeedsRehashTest {

    /** 52 characters; only the shape of the rest matters to the rehash rule. */
    private static final String TAIL = "!AzpghLTQbsaP0Jy.E09Q.IPM5F8kxp9PK5VQImT2W2c2NL5E3dT";

    @Test
    void currentWpHashesAreKept() {
        assertThat(WpPasswords.needsRehash("$wp$2y$12$2AzpghLTQbsaP0Jy.E09Q.IPM5F8kxp9PK5VQImT2W2c2NL5E3dTW"))
                .isFalse(); // 12.1#12
        assertThat(WpPasswords.needsRehash("$wp$2y$12$" + TAIL + "W")).isFalse();
    }

    @Test
    void otherCostsAreReplaced() {
        assertThat(WpPasswords.needsRehash("$wp$2y$05$5KLnDnATV5NLkbHSOiGp7OBSSVzspqjDios4TlIEUuIwunPkuBtvi"))
                .isTrue(); // 12.1#2
        assertThat(WpPasswords.needsRehash("$wp$2y$10$" + TAIL + "W")).isTrue();
        assertThat(WpPasswords.needsRehash("$wp$2y$13$" + TAIL + "W")).isTrue();
    }

    @Test
    void everythingWithoutTheWpPrefixIsReplaced() {
        String[] hashes = {
            "5f4dcc3b5aa765d61d8327deb882cf99",
            "$P$BSALTsaltoN07aSztUl8KSk2X7DmZP0",
            "$H$9abcdefghreUCnbbQX76dJT2aHvsT6.",
            "$2y$12$wdrQcFlTn5WvIrrvs3dKEeu7N8ZcoBm7iO/ECJwt9Bk8obdARsFvK",
            "$generic$F6W18u0QEytvRVb59DemVWRxyTxU6vg_VK0s_f-h",
            "",
            "*",
            "$WP$2y$12$" + TAIL + "W"
        };
        for (String hash : hashes) {
            assertThat(WpPasswords.needsRehash(hash)).as(hash).isTrue();
        }
    }

    @Test
    void onlyTwoYCountsAsCurrent() {
        assertThat(WpPasswords.needsRehash("$wp$2a$12$" + TAIL + "W")).isTrue();
        assertThat(WpPasswords.needsRehash("$wp$2b$12$" + TAIL + "W")).isTrue();
    }

    @Test
    void theRestMustBeSixtyBytes() {
        assertThat(WpPasswords.needsRehash("$wp$2y$12$" + TAIL)).isTrue();
        assertThat(WpPasswords.needsRehash("$wp$2y$12$" + TAIL + "WW")).isTrue();
        assertThat(WpPasswords.needsRehash("$wp")).isTrue();
        assertThat(WpPasswords.needsRehash("$wp$2y$12$short")).isTrue();
    }

    @Test
    void costIsReadLikeCScanf() {
        assertThat(WpPasswords.needsRehash("$wp$2y$+12$" + TAIL)).isFalse();
        assertThat(WpPasswords.needsRehash("$wp$2y$ 12$" + TAIL)).isFalse();
        assertThat(WpPasswords.needsRehash("$wp$2y$\t12$" + TAIL)).isFalse();
        assertThat(WpPasswords.needsRehash("$wp$2y$012$" + TAIL)).isFalse();
        assertThat(WpPasswords.needsRehash("$wp$2y$-12$" + TAIL)).isTrue();
        assertThat(WpPasswords.needsRehash("$wp$2y$xx$" + TAIL + "W")).isFalse(); // no digits: PHP keeps 12
        assertThat(WpPasswords.needsRehash("$wp$2y$12x" + TAIL + "W")).isFalse();
        assertThat(WpPasswords.needsRehash("$wp$2y$+1$x" + TAIL)).isTrue();
    }

    @Test
    void scannedCostSaturatesLongDigitRuns() {
        byte[] digits = PhpBytes.utf8("$2y$" + "9".repeat(40));
        assertThat(WpPasswords.scannedCost(digits, 4)).isGreaterThan(WpPasswords.TARGET_COST);
    }

    @Test
    void nullThrows() {
        assertThatThrownBy(() -> WpPasswords.needsRehash(null)).isInstanceOf(NullPointerException.class);
    }
}
