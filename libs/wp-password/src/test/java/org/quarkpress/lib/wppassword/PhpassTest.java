package org.quarkpress.lib.wppassword;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhpassTest {

    private static byte[] utf8(String text) {
        return PhpBytes.utf8(text);
    }

    @Test
    void reproducesWordPressFixedSaltHashes() {
        String[][] vectors = {
            {"password", "$P$BSALTsaltoN07aSztUl8KSk2X7DmZP0"},
            {"password", "$P$5SALTsaltMrbDGQINQ30D.ZQjUWYKP1"},
            {"password", "$P$9abcdefghreUCnbbQX76dJT2aHvsT6."},
            {"", "$P$BSALTsalt/LPugtOMP2yep0ICGvuNj0"},
            {"", "$P$5SALTsaltWjm8tIG/sBJ8onhnjGP/y/"},
            {"", "$P$9abcdefghnomoWFux1fppTjplIhy411"},
            {"pässwörd 🔑 Ωμέγα", "$P$BSALTsaltzR4HWGzl98UaDUemDzovg/"},
            {"pässwörd 🔑 Ωμέγα", "$P$5SALTsaltLSluRAkUuq5qau/nhcxeU."},
            {"pässwörd 🔑 Ωμέγα", "$P$9abcdefghViWHBZnkjczmspYc8bicr/"}
        };
        for (String[] v : vectors) {
            byte[] stored = utf8(v[1]);
            assertThat(Phpass.compute(utf8(v[0]), stored)).as(v[1]).isEqualTo(stored);
            assertThat(Phpass.verify(utf8(v[0]), stored)).as(v[1]).isTrue();
        }
    }

    @Test
    void phpbbIdUsesTheSameAlgorithm() {
        String[][] vectors = {
            {"password", "$H$9abcdefghreUCnbbQX76dJT2aHvsT6."},
            {"", "$H$9abcdefghnomoWFux1fppTjplIhy411"},
            {"pässwörd 🔑 Ωμέγα", "$H$9abcdefghViWHBZnkjczmspYc8bicr/"}
        };
        for (String[] v : vectors) {
            assertThat(Phpass.verify(utf8(v[0]), utf8(v[1]))).as(v[1]).isTrue();
        }
    }

    @Test
    void wrongPasswordsFail() {
        byte[] stored = utf8("$P$BSALTsaltoN07aSztUl8KSk2X7DmZP0");
        assertThat(Phpass.verify(utf8("Password"), stored)).isFalse();
        assertThat(Phpass.verify(utf8(" password "), stored)).isFalse();
        assertThat(Phpass.verify(utf8("password\0"), stored)).isFalse();
    }

    @Test
    void countCharacterMustMean7To20() {
        // count characters '4' (2^6) and 'T' (2^31) make WordPress fail; 'J' (2^21) is refused to bound the work
        assertThat(Phpass.compute(utf8("password"), utf8("$P$4SALTsalt"))).isNull();
        assertThat(Phpass.compute(utf8("password"), utf8("$P$TSALTsalt"))).isNull();
        assertThat(Phpass.verify(utf8("password"), utf8("$P$4SALTsaltlnR5qid53on8Hq4IEtwEw.")))
                .isFalse();
        assertThat(Phpass.verify(utf8("password"), utf8("$P$TSALTsaltlnR5qid53on8Hq4IEtwEw.")))
                .isFalse();
        assertThat(Phpass.compute(utf8("password"), utf8("$P$!SALTsalt"))).isNull();
        assertThat(Phpass.compute(utf8("password"), utf8("$P$éSALTsalt"))).isNull();
    }

    @Test
    void storedHashMustBe34Bytes() {
        assertThat(Phpass.verify(utf8("password"), utf8("$P$BSALTsaltoN07aSztUl8KSk2X7DmZP")))
                .isFalse();
        assertThat(Phpass.verify(utf8("password"), utf8("$P$BSALTsaltoN07aSztUl8KSk2X7DmZP0x")))
                .isFalse();
    }

    @Test
    void saltIsEightBytesNotEightCharacters() {
        // made by crypt_private("password", "$P$Béééé"): four 2-byte characters are the 8 salt bytes
        assertThat(Phpass.compute(utf8("password"), utf8("$P$Béééé")))
                .isEqualTo(utf8("$P$Bééééz5WL70J/T9xpRy0a9NQFX0"));
    }

    @Test
    void unusableSettingsAreRefused() {
        assertThat(Phpass.compute(utf8("password"), utf8("$P$BSALT"))).isNull();
        assertThat(Phpass.compute(utf8("password"), utf8("$X$BSALTsalt"))).isNull();
        assertThat(Phpass.compute(utf8("password"), utf8("P$BSALTsalt"))).isNull();
    }

    @Test
    void countTwentyIsTheLargestAcceptedAndTwentyOneIsRefused() {
        // both made by WordPress's crypt_private for "password"; count 21 is refused here without computing
        byte[] twenty = utf8("$P$ISALTsaltAfa/ywetlBTKYiuuYc4Tv.");
        assertThat(Phpass.verify(utf8("password"), twenty)).isTrue();
        assertThat(Phpass.verify(utf8("passwore"), twenty)).isFalse();
        byte[] twentyOne = utf8("$P$JSALTsalt2XOQEEDeEJpVehRAYfnLS0");
        assertThat(Phpass.compute(utf8("password"), twentyOne)).isNull();
        assertThat(Phpass.verify(utf8("password"), twentyOne)).isFalse();
    }
}
