package org.quarkword.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Emails#isEmail}, with the cases of rest-wp-v2's e-mail format check (where the scan lived before) and further
 * addresses whose {@code is_email} answer WordPress 7.1.3 gave (PHP 8.4.27).
 */
class EmailsTest {

    @Test
    void addressesWordPressAccepts() {
        for (String ok : List.of(
                "a@b.co",
                "ab@b.c",
                ".a@c.co",
                "a..b@c.co",
                "a@B.CO",
                "a@b.c-o",
                "!#$%&'*+/=?^_`{|}~@c.co",
                "a@b.c.d.e.f",
                "xx@x.x",
                "A@EXAMPLE.COM",
                "a@xn--bcher-kva.de",
                "a@1.2.3",
                "1@2.34")) {
            assertThat(Emails.isEmail(ok)).as(ok).isTrue();
        }
    }

    @Test
    void addressesWordPressRefuses() {
        for (String bad : List.of(
                "a@b.c",
                "@b.co",
                "a@@b.co",
                "a@b..co",
                "a@.b.co",
                "a@b.co.",
                "a@-b.co",
                "a@b-.co",
                "a@b_c.co",
                "a b@c.co",
                "a@b",
                "é@b.co",
                "a@é.co",
                "a@b.co ",
                "\ta@b.co",
                "a\"b@c.co",
                "a@1.2",
                "x@x.x",
                " jdoe@example.com ",
                "x@y.com\r\nBcc: evil@z.com",
                "a\"<script>@example.com",
                "not-an-email",
                "üser@example.com",
                "a@b@c.co",
                "a@b.-co",
                "a@b.co-",
                "a@b.co\n",
                "a@b.co\u0000",
                "")) {
            assertThat(Emails.isEmail(bad)).as(bad).isFalse();
        }
    }

    @Test
    void localPartEndingInALineFeedIsRefusedThoughWordPressAcceptsIt() {
        assertThat(Emails.isEmail("a\n@b.co")).isFalse();
    }

    @Test
    void longInputIsScannedOnce() {
        assertThat(Emails.isEmail("a".repeat(1_000_000) + "@b.co")).isTrue();
    }

    @Test
    void nullThrows() {
        assertThatThrownBy(() -> Emails.isEmail(null)).isInstanceOf(NullPointerException.class);
    }
}
