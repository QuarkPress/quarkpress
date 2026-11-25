package org.quarkword.lib.wppassword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class PortableHashTest {

    /** Made by WordPress 7.1.3's {@code PasswordHash(8, true)->HashPassword("s3")} through password-driver.php. */
    private static final String WORDPRESS_S3 = "$P$BZCIJPM9ItMyWwAIMeTCxVh0Ia5ViV0";

    /** A source of randomness that hands out {@code bytes} in order. */
    private static SecureRandom fixed(int... bytes) {
        return new SecureRandom() {
            private int next;

            @Override
            public void nextBytes(byte[] out) {
                for (int i = 0; i < out.length; i++) {
                    out[i] = (byte) bytes[next++];
                }
            }
        };
    }

    @Test
    void aPortableHashHasWordPresssShapeAndVerifies() {
        String hash = WpPasswords.hashPortable("s3", 8, new SecureRandom());
        assertThat(hash).hasSize(34).startsWith("$P$B");
        assertThat(WpPasswords.check("s3", hash)).isTrue();
        assertThat(WpPasswords.check("x", hash)).isFalse();
        assertThat(WpPasswords.detect(hash)).isEqualTo(HashType.PHPASS);
    }

    @Test
    void wordPresssOwnPortableHashVerifies() {
        assertThat(WpPasswords.check("s3", WORDPRESS_S3)).isTrue();
        assertThat(WpPasswords.check("s4", WORDPRESS_S3)).isFalse();
    }

    @Test
    void theSaltIsSixRandomBytesWrittenAsEightCharacters() {
        // crypt_private("s3", gensalt_private("\x00\x01\x02\x03\x04\x05")) and of "\xff\xfe\xfd\xfc\xfb\xfa" with
        // PasswordHash(8, true), recorded with /usr/bin/php (slice harness postpass.php)
        assertThat(WpPasswords.hashPortable("s3", 8, fixed(0, 1, 2, 3, 4, 5)))
                .isEqualTo("$P$B.2U.1EE/dMhBep4Vg9bYh03J1YC6x0");
        assertThat(WpPasswords.hashPortable("s3", 8, fixed(0xff, 0xfe, 0xfd, 0xfc, 0xfb, 0xfa)))
                .isEqualTo("$P$BzvTzwjjyCOLjwlxa0C0zZTJ./yH26.");
    }

    @Test
    void theCountCharacterIsTheAlphabetCharacterAtLog2RoundsPlusFive() {
        assertThat(WpPasswords.hashPortable("s3", 4, fixed(0, 0, 0, 0, 0, 0))).startsWith("$P$7........");
        assertThat(WpPasswords.hashPortable("s3", 15, fixed(0, 0, 0, 0, 0, 0))).startsWith("$P$I........");
        assertThatThrownBy(() -> WpPasswords.hashPortable("s3", 3, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WpPasswords.hashPortable("s3", 16, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwordsOver4096BytesAreRefused() {
        // WordPress's HashPassword answers "*" for them; this library refuses to make a hash
        assertThat(WpPasswords.check(
                        "a".repeat(4096), WpPasswords.hashPortable("a".repeat(4096), 8, new SecureRandom())))
                .isTrue();
        assertThatThrownBy(() -> WpPasswords.hashPortable("a".repeat(4097), 8, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("aaaa");
    }

    @Test
    void nullArgumentsThrow() {
        assertThatThrownBy(() -> WpPasswords.hashPortable(null, 8, new SecureRandom()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WpPasswords.hashPortable("s3", 8, null)).isInstanceOf(NullPointerException.class);
    }
}
