package org.quarkpress.lib.wppassword;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DetectTest {

    private static final String BCRYPT = "$2y$05$06gRD94okD45dxtYQWn5quHxb9lT1cn7c8781KPpb5OP8SoVDUyq2";

    @Test
    void lowercaseMd5HexIsMd5() {
        assertThat(WpPasswords.detect("5f4dcc3b5aa765d61d8327deb882cf99")).isEqualTo(HashType.MD5);
        assertThat(WpPasswords.detect("d41d8cd98f00b204e9800998ecf8427e")).isEqualTo(HashType.MD5);
    }

    @Test
    void everyOtherStringOf32BytesOrFewerIsUnknown() {
        String[] shortOnes = {
            "",
            " ",
            "*",
            "abJnggxhB/yWI",
            "_J9..saltJW8FtKdEkNM",
            "5F4DCC3B5AA765D61D8327DEB882CF99",
            "5f4dcc3b5aa765d61d8327deb882cf9",
            "5f4dcc3b5aa765d61d8327deb882cfzz",
            "$P$BSALTsalt",
            "$wp$2y$05$not-a-real-hash",
            "\u00e9".repeat(16)
        };
        for (String hash : shortOnes) {
            assertThat(WpPasswords.detect(hash)).as(hash).isEqualTo(HashType.UNKNOWN);
        }
    }

    @Test
    void nullIsUnknown() {
        assertThat(WpPasswords.detect(null)).isEqualTo(HashType.UNKNOWN);
    }

    @Test
    void prefixesAreTriedInWordPressOrder() {
        assertThat(WpPasswords.detect("$wp$2y$05$5KLnDnATV5NLkbHSOiGp7OBSSVzspqjDios4TlIEUuIwunPkuBtvi"))
                .isEqualTo(HashType.WP_BCRYPT);
        assertThat(WpPasswords.detect("$wp" + "x".repeat(40))).isEqualTo(HashType.WP_BCRYPT);
        assertThat(WpPasswords.detect("$P$BSALTsaltoN07aSztUl8KSk2X7DmZP0")).isEqualTo(HashType.PHPASS);
        assertThat(WpPasswords.detect("$H$9abcdefghreUCnbbQX76dJT2aHvsT6.")).isEqualTo(HashType.PHPASS_PHPBB);
        assertThat(WpPasswords.detect(BCRYPT)).isEqualTo(HashType.BCRYPT);
        for (String id : new String[] {"$2a$", "$2b$", "$2x$"}) {
            assertThat(WpPasswords.detect(id + BCRYPT.substring(4))).as(id).isEqualTo(HashType.BCRYPT);
        }
        assertThat(WpPasswords.detect("$generic$F6W18u0QEytvRVb59DemVWRxyTxU6vg_VK0s_f-h"))
                .isEqualTo(HashType.WP_FAST_HASH);
    }

    @Test
    void bcryptMustBeSixtyBytes() {
        assertThat(WpPasswords.detect(BCRYPT.substring(0, 59))).isEqualTo(HashType.UNKNOWN);
        assertThat(WpPasswords.detect(BCRYPT + "x")).isEqualTo(HashType.UNKNOWN);
    }

    @Test
    void prefixesAreCaseSensitive() {
        assertThat(WpPasswords.detect("$WP$2y$05$5KLnDnATV5NLkbHSOiGp7OBSSVzspqjDios4TlIEUuIwunPkuBtvi"))
                .isEqualTo(HashType.UNKNOWN);
        assertThat(WpPasswords.detect("$p$BSALTsaltoN07aSztUl8KSk2X7DmZP0")).isEqualTo(HashType.UNKNOWN);
        assertThat(WpPasswords.detect("$2Y" + BCRYPT.substring(3))).isEqualTo(HashType.UNKNOWN);
        assertThat(WpPasswords.detect("$GENERIC$F6W18u0QEytvRVb59DemVWRxyTxU6vg_VK0s_f-h"))
                .isEqualTo(HashType.UNKNOWN);
    }

    @Test
    void cryptAndArgon2FormatsAreUnknown() {
        String[] others = {
            "$1$saltsalt$qjXMvbEw8oaL.CzflDtaK/",
            "$5$rounds=5000$saltsaltsaltsalt$WsFBeg1qQ90JL3VkUTuM7xVV/5njhLngIVm6ftSnBR2",
            "$6$saltsaltsaltsalt$bcXJ8qxwY5sQ4v8MTl.0B1jeZ0z0JlA9jjmbUoCJZ.1wYXiLTU.q2ILyrDJLm890lyfuF7sWAeli0yjOyFPkf0",
            "$argon2id$v=19$m=1024,t=1,p=1$RnFCRy5ScWE0ZDdsRWJjdw$poLQvcfX1R0Yg/ueRzX4/mV78rL5y0zfimuaxHnHJdw"
        };
        for (String hash : others) {
            assertThat(WpPasswords.detect(hash)).as(hash).isEqualTo(HashType.UNKNOWN);
        }
    }

    @Test
    void lengthsAreUtf8Bytes() {
        // 30 characters, 34 bytes: WordPress measures 34, so this goes to phpass (it verifies "password" there)
        assertThat(WpPasswords.detect("$P$B\u00e9\u00e9\u00e9\u00e9z5WL70J/T9xpRy0a9NQFX0"))
                .isEqualTo(HashType.PHPASS);
    }
}
