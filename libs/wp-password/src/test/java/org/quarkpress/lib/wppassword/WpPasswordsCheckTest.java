package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class WpPasswordsCheckTest {

    private static final String WP_PASSWORD =
            "$wp$2y$05$5KLnDnATV5NLkbHSOiGp7OBSSVzspqjDios4TlIEUuIwunPkuBtvi"; // 12.1#2
    private static final String PHPASS_PASSWORD = "$P$BSALTsaltoN07aSztUl8KSk2X7DmZP0"; // 12.3#8
    private static final String BCRYPT_PASSWORD =
            "$2y$05$06gRD94okD45dxtYQWn5quHxb9lT1cn7c8781KPpb5OP8SoVDUyq2"; // 12.2#2
    private static final String MD5_PASSWORD = "5f4dcc3b5aa765d61d8327deb882cf99"; // 12.5#2
    private static final String APP_PASSWORD = "abcdEFGH1234ijklMNOP5678";
    private static final String APP_HASH = "$generic$F6W18u0QEytvRVb59DemVWRxyTxU6vg_VK0s_f-h"; // 12.7#1

    @Test
    void md5RuleCoversEveryShortHash() {
        assertThat(WpPasswords.check("password", MD5_PASSWORD)).isTrue();
        assertThat(WpPasswords.check("", "d41d8cd98f00b204e9800998ecf8427e")).isTrue();
        assertThat(WpPasswords.check("password", MD5_PASSWORD.toUpperCase(Locale.ROOT)))
                .isFalse();
        assertThat(WpPasswords.check("password", MD5_PASSWORD.substring(0, 31))).isFalse();
        assertThat(WpPasswords.check("", "")).isFalse();
        assertThat(WpPasswords.check("*", "*")).isFalse();
        assertThat(WpPasswords.check("password", "abJnggxhB/yWI")).isFalse();
        assertThat(WpPasswords.check("password", "_J9..saltJW8FtKdEkNM")).isFalse();
    }

    @Test
    void md5RuleRunsBeforeThe4096ByteLimit() {
        assertThat(WpPasswords.check("x".repeat(5000), "a6bb7bde3251ca2d810d32dadd9e8ae7"))
                .isTrue(); // 12.5#8
        assertThat(WpPasswords.check("x".repeat(5000), "$P$BSALTsaltaYJVJw9N5kh9bDQjaay8o0"))
                .isFalse(); // 12.3#23
    }

    @Test
    void limitIsInclusiveAt4096Bytes() {
        // wp_hash_password("x" * 4096) at cost 5 through wp_hash_password_options; wp_check_password: true / false
        String wp = "$wp$2y$05$.9Cn16EvthKGMIgDm3BKWuXscTZOEAk43h6X0xkQh9XvB94sPEV.2";
        assertThat(WpPasswords.check("x".repeat(4096), wp)).isTrue();
        assertThat(WpPasswords.check("x".repeat(4097), wp)).isFalse();
        // crypt_private("x" * 4096, "$P$BSALTsalt"); wp_check_password: true
        assertThat(WpPasswords.check("x".repeat(4096), "$P$BSALTsaltIAjI22gtTX3z/B1yBCV39/"))
                .isTrue();
    }

    @Test
    void limitCountsUtf8Bytes() {
        String password = "x".repeat(4095) + "\u00e9"; // 4096 characters, 4097 bytes
        // crypt_private of exactly these bytes; wp_check_password refuses by length: false
        assertThat(WpPasswords.check(password, "$P$BSALTsaltlJ8gKkIiuEw8rl8xErfQc1"))
                .isFalse();
        // md5 of these bytes; the MD5 rule has no limit: true
        assertThat(WpPasswords.check(password, "a5da82bee327f9a427fa98caefb364f1"))
                .isTrue();
    }

    @Test
    void wpPrefixUsesTheHmacPrehash() {
        assertThat(WpPasswords.check("password", WP_PASSWORD)).isTrue();
        assertThat(WpPasswords.check("Password", WP_PASSWORD)).isFalse(); // 12.1#14
        assertThat(WpPasswords.check("password", WP_PASSWORD.substring(0, WP_PASSWORD.length() - 1)))
                .isFalse(); // 12.1#15
        assertThat(WpPasswords.check("password", WP_PASSWORD.substring(3))).isFalse(); // 12.1#16
        assertThat(WpPasswords.check(
                        "ZEIBxmCIjkyF8MNbMmsrJOPmp2/ioyAvd90BaIOY1lr6z3gfcWZusfjOv0kWasvS", WP_PASSWORD.substring(3)))
                .isTrue(); // 12.1#17
        assertThat(WpPasswords.check("password", "$WP" + WP_PASSWORD.substring(3)))
                .isFalse(); // 12.1#18
        assertThat(WpPasswords.check("password", "$wp" + WP_PASSWORD)).isFalse(); // 12.1#19
        assertThat(WpPasswords.check("password", "$wp$2y$05$IUXukhezVLafRML7W7HmauiLFGafUPNhu0Ehmpwac.x0Sw3sMSiBO"))
                .isFalse(); // 12.1#21: $wp over bcrypt of the raw password
        assertThat(WpPasswords.check("password", "$wp$2a" + WP_PASSWORD.substring(6)))
                .isTrue(); // 12.1#22
        assertThat(WpPasswords.check("password", "$wp$2b" + WP_PASSWORD.substring(6)))
                .isTrue();
        assertThat(WpPasswords.check("password", "$wp$2x" + WP_PASSWORD.substring(6)))
                .isFalse(); // D1; WordPress: true
    }

    @Test
    void wpPrefixKeepsNulBytesAndBytesAfter72() {
        String nul = "$wp$2y$05$6zCr1nBN7TbwA6nfWWFOEe3g2O.QwERkv7PIns8R9L1FqSMd3orNe"; // 12.1#7, #11
        assertThat(WpPasswords.check("a\0b", nul)).isTrue();
        assertThat(WpPasswords.check("a", nul)).isFalse();
        String long100 = "$wp$2y$05$V/saDLxE32u1uAT1iRlhl.cCC.tTgIpwsEJyOum.adlReS38zrPG2"; // 12.1#4, #10
        assertThat(WpPasswords.check("a".repeat(72) + "TAIL-" + "b".repeat(23), long100))
                .isTrue();
        assertThat(WpPasswords.check("a".repeat(72) + "DIFF-" + "b".repeat(23), long100))
                .isFalse();
    }

    @Test
    void checkDoesNotTrim() {
        // wp_hash_password("  padded pw \t") trims before hashing; checking does not (12.1#8, #9)
        String trimmed = "$wp$2y$05$u5CG9fY.Mkn/HllU7qH7z.ClT80RYJScO2i1JtwnDUsV3gGgBiPda";
        assertThat(WpPasswords.check("  padded pw \t", trimmed)).isFalse();
        assertThat(WpPasswords.check("padded pw", trimmed)).isTrue();
        assertThat(WpPasswords.check(" password ", BCRYPT_PASSWORD)).isFalse(); // 12.2#9
        assertThat(WpPasswords.check(" password ", "$P$BwrVffugilnR5qid53on8Hq4IEtwEw."))
                .isFalse(); // 12.3#18
    }

    @Test
    void prehashIsStandardBase64OfHmacSha384() {
        assertThat(new String(WpPasswords.prehash(PhpBytes.utf8("password")), US_ASCII))
                .isEqualTo("ZEIBxmCIjkyF8MNbMmsrJOPmp2/ioyAvd90BaIOY1lr6z3gfcWZusfjOv0kWasvS");
        assertThat(new String(WpPasswords.prehash(new byte[0]), US_ASCII))
                .isEqualTo("sKe/JAbQCgfkGVY+1twpKmw0ABXcUhnid83TItf/6UyNopuWvOCYbEXl7r2MD45x");
    }

    @Test
    void phpassAndPlainBcrypt() {
        assertThat(WpPasswords.check("password", PHPASS_PASSWORD)).isTrue();
        assertThat(WpPasswords.check("password", BCRYPT_PASSWORD)).isTrue();
        assertThat(WpPasswords.check("password", "$2a" + BCRYPT_PASSWORD.substring(3)))
                .isTrue(); // 12.2#10
        assertThat(WpPasswords.check("password", "$2b" + BCRYPT_PASSWORD.substring(3)))
                .isTrue(); // 12.2#11
        assertThat(WpPasswords.check("a\0b", "$2y$05$2qAuGekcbESQWcvdM6anROQm6sSjDpF5zbSmKgHpAQCZZAnhZJP0m"))
                .isTrue(); // 12.2#7: bcrypt of "a"
    }

    @Test
    void formatsWordPressCoreDoesNotAcceptFail() {
        assertThat(WpPasswords.check("password", "$H$9abcdefghreUCnbbQX76dJT2aHvsT6."))
                .isFalse(); // 12.4#1
        assertThat(WpPasswords.check(APP_PASSWORD, APP_HASH)).isFalse(); // 12.7#11
    }

    @Test
    void unsupportedFormatsFail() {
        // D1, D2: WordPress verifies all of these through password_verify (12.2#12, 12.6#1-3, 12.6#6)
        assertThat(WpPasswords.check("password", "$2x" + BCRYPT_PASSWORD.substring(3)))
                .isFalse();
        assertThat(WpPasswords.check("password", "$1$saltsalt$qjXMvbEw8oaL.CzflDtaK/"))
                .isFalse();
        assertThat(WpPasswords.check(
                        "password", "$5$rounds=5000$saltsaltsaltsalt$WsFBeg1qQ90JL3VkUTuM7xVV/5njhLngIVm6ftSnBR2"))
                .isFalse();
        assertThat(
                        WpPasswords.check(
                                "password",
                                "$6$saltsaltsaltsalt$bcXJ8qxwY5sQ4v8MTl.0B1jeZ0z0JlA9jjmbUoCJZ.1wYXiLTU.q2ILyrDJLm890lyfuF7sWAeli0yjOyFPkf0"))
                .isFalse();
        assertThat(
                        WpPasswords.check(
                                "password",
                                "$argon2id$v=19$m=1024,t=1,p=1$RnFCRy5ScWE0ZDdsRWJjdw$poLQvcfX1R0Yg/ueRzX4/mV78rL5y0zfimuaxHnHJdw"))
                .isFalse();
    }

    @Test
    void byteOverloadTakesPasswordsThatAreNotUtf8() {
        assertThat(WpPasswords.check(
                        new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xa3},
                        "$2y$05$/OK.fbVrR/bpIqNJ5ianF.CE5elHaaO4EbggVDjb8P19RukzXSM3e"))
                .isTrue();
        // md5 of the bytes ED A0 80 (an encoded surrogate, not valid UTF-8)
        assertThat(WpPasswords.check(
                        new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, "546d3dc8de10fbf8b448f678a47901e4"))
                .isTrue();
    }

    @Test
    void unpairedSurrogateIsEncodedAsQuestionMark() {
        assertThat(WpPasswords.check("\uD800", "d1457b72c3fb323a2671125aef3eab5d"))
                .isTrue(); // md5("?")
        assertThat(WpPasswords.check("\uD800", "546d3dc8de10fbf8b448f678a47901e4"))
                .isFalse();
    }

    @Test
    void multiByteSaltIsCountedInBytes() {
        // 30 characters, 34 bytes; crypt_private("password", "$P$Béééé"); wp_check_password: true
        assertThat(WpPasswords.check("password", "$P$B\u00e9\u00e9\u00e9\u00e9z5WL70J/T9xpRy0a9NQFX0"))
                .isTrue();
    }

    @Test
    void surroundingWhitespaceInStoredHashIsNotTrimmed() {
        assertThat(WpPasswords.check("password", PHPASS_PASSWORD + "\n")).isFalse();
        assertThat(WpPasswords.check("password", " " + MD5_PASSWORD)).isFalse();
        assertThat(WpPasswords.check("password", BCRYPT_PASSWORD + " ")).isFalse();
        assertThat(WpPasswords.detect(PHPASS_PASSWORD + "\n")).isEqualTo(HashType.PHPASS);
    }

    @Test
    void verifyAlsoTriesTheSlashedForm() {
        // hash of addslashes("O'Brien\"q"), as WordPress's screens store it (12.1#24, #25)
        String slashedStore = "$wp$2y$05$MGAZIdLTp3PPgXo8n4oLtOUsjqLQ5AR38ZG0WCLdKvpmfQVEWKrOK";
        assertThat(WpPasswords.check("O'Brien\"q", slashedStore)).isFalse();
        assertThat(WpPasswords.verify("O'Brien\"q", slashedStore)).isTrue();
        assertThat(WpPasswords.verify("O'Brien\"x", slashedStore)).isFalse();
        // hash of the raw form, as wp_set_password called directly stores it (12.1#6)
        String rawStore = "$wp$2y$05$ERRyu5uMaffykVJ7qIqBse.7Cl4IFaqtUG5ZqjIrcDsHX21ajOs1O";
        assertThat(WpPasswords.verify("O'Brien\"q", rawStore)).isTrue();
        assertThat(WpPasswords.verify("password", WP_PASSWORD)).isTrue();
        assertThat(WpPasswords.verify("Password", WP_PASSWORD)).isFalse();
    }

    @Test
    void checkFastHashVerifiesApplicationPasswords() {
        assertThat(WpPasswords.checkFastHash(APP_PASSWORD, APP_HASH)).isTrue();
        assertThat(WpPasswords.checkFastHash("", "$generic$XJQGK2eUryTa4vHbU0bDv1Ml3wGPwaC1ATczq0o_"))
                .isTrue(); // 12.7#4
        assertThat(WpPasswords.checkFastHash("abcd EFGH 1234 ijkl MNOP 5678", APP_HASH))
                .isFalse(); // 12.7#6
        assertThat(WpPasswords.checkFastHash("abcdefgh1234ijklmnop5678", APP_HASH))
                .isFalse(); // 12.7#8
        assertThat(WpPasswords.checkFastHash(APP_PASSWORD, APP_HASH.substring(0, APP_HASH.length() - 1)))
                .isFalse(); // 12.7#9
        assertThat(WpPasswords.checkFastHash(APP_PASSWORD, APP_HASH + "=")).isFalse();
        assertThat(WpPasswords.checkFastHash(APP_PASSWORD, "$GENERIC$" + APP_HASH.substring(9)))
                .isFalse();
    }

    @Test
    void checkFastHashSendsOtherHashesToCheck() {
        // WP_Application_Passwords::check_password results
        assertThat(WpPasswords.checkFastHash(APP_PASSWORD, "$P$BCjHaW4bMyDtBnWmCbMht5WdoRcSF8/"))
                .isTrue(); // 12.7#10
        assertThat(WpPasswords.checkFastHash("password", MD5_PASSWORD)).isTrue();
        assertThat(WpPasswords.checkFastHash("password", BCRYPT_PASSWORD)).isTrue();
        assertThat(WpPasswords.checkFastHash("password", "$H$9abcdefghreUCnbbQX76dJT2aHvsT6."))
                .isFalse();
    }

    @Test
    void fastHashMatchesWordPress() {
        // wp_fast_hash results
        assertThat(new String(WpPasswords.fastHash(new byte[] {'a', 0, 'b'}), US_ASCII))
                .isEqualTo("$generic$aIvQ41bAHkbmnz3Ltjiem39O-qdlJhk2RYY-OKy5");
        assertThat(new String(
                        WpPasswords.fastHash(
                                PhpBytes.utf8("p\u00e4ssw\u00f6rd \uD83D\uDD11 \u03a9\u03bc\u03ad\u03b3\u03b1")),
                        US_ASCII))
                .isEqualTo("$generic$_-w6xqHc2MO471KBFJBPNyiEkoGop5mAyFgn3vVe");
    }

    @Test
    void wpBcryptHashRoundTrips() {
        byte[] salt = Bcrypt.decodeSalt("CCCCCCCCCCCCCCCCCCCCC.");
        String hash = WpPasswords.wpBcryptHash(PhpBytes.utf8("correct horse"), 4, salt);
        assertThat(hash).startsWith("$wp$2y$04$CCCCCCCCCCCCCCCCCCCCC.").hasSize(63);
        assertThat(WpPasswords.check("correct horse", hash)).isTrue();
        assertThat(WpPasswords.check("correct horse ", hash)).isFalse();
    }

    @Test
    void nullArgumentsThrow() {
        assertThatThrownBy(() -> WpPasswords.check((String) null, MD5_PASSWORD))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WpPasswords.check("password", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WpPasswords.check((byte[]) null, MD5_PASSWORD))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WpPasswords.check(new byte[0], null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WpPasswords.verify(null, MD5_PASSWORD)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WpPasswords.verify("password", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WpPasswords.checkFastHash(null, APP_HASH)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WpPasswords.checkFastHash(APP_PASSWORD, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void malformedHashesReturnFalse() {
        String[] malformed = {
            "$",
            "$wp",
            "$wp$",
            "$P$",
            "$2y$",
            "$generic$",
            "\0".repeat(40),
            "\u00e9".repeat(40),
            "$wp" + "\u00e9".repeat(30)
        };
        for (String hash : malformed) {
            assertThat(WpPasswords.check("password", hash)).as(hash).isFalse();
            assertThat(WpPasswords.verify("password", hash)).as(hash).isFalse();
            assertThat(WpPasswords.checkFastHash("password", hash)).as(hash).isFalse();
        }
    }
}
