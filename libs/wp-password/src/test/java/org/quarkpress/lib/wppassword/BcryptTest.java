package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BcryptTest {

    /** bcrypt of "password" at cost 05, made by PHP password_hash (reference vector 12.2#2). */
    private static final String PASSWORD_HASH = "$2y$05$06gRD94okD45dxtYQWn5quHxb9lT1cn7c8781KPpb5OP8SoVDUyq2";

    private static final String BASE = "abcdefghi".repeat(9);

    private static byte[] ascii(String text) {
        return text.getBytes(US_ASCII);
    }

    private static byte[] hex(String text) {
        return HexFormat.of().parseHex(text);
    }

    static Stream<Arguments> publishedVectors() {
        return Stream.of(
                Arguments.of(ascii("U*U"), "$2a$05$CCCCCCCCCCCCCCCCCCCCC.E5YPO9kmyuRGyh0XouQYb4YMJKvyOeW"),
                Arguments.of(ascii("U*U*"), "$2a$05$CCCCCCCCCCCCCCCCCCCCC.VGOzA784oUp/Z0DY336zx7pLYAy0lwK"),
                Arguments.of(ascii("U*U*U"), "$2a$05$XXXXXXXXXXXXXXXXXXXXXOAcXxm9kjPGEMsLznoKqmqw7tc8WCx4a"),
                Arguments.of(
                        ascii("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
                                + "chars after 72 are ignored"),
                        "$2a$05$abcdefghijklmnopqrstuu5s2v8.iXieOjg/.AySBTTZIIVFJeBui"),
                Arguments.of(hex("ffffa3"), "$2y$05$/OK.fbVrR/bpIqNJ5ianF.CE5elHaaO4EbggVDjb8P19RukzXSM3e"),
                Arguments.of(hex("ffffa3"), "$2a$05$/OK.fbVrR/bpIqNJ5ianF.nqd1wy.pTMdcvrRWxyiGL2eMz.2a85."),
                Arguments.of(hex("ffffa3"), "$2b$05$/OK.fbVrR/bpIqNJ5ianF.CE5elHaaO4EbggVDjb8P19RukzXSM3e"),
                Arguments.of(hex("a3"), "$2y$05$/OK.fbVrR/bpIqNJ5ianF.Sa7shbm4.OzKpvFnX1pQLmQW96oUlCq"),
                Arguments.of(hex("a3"), "$2a$05$/OK.fbVrR/bpIqNJ5ianF.Sa7shbm4.OzKpvFnX1pQLmQW96oUlCq"),
                Arguments.of(
                        hex("ffa33334ffffffa3333435"), "$2y$05$/OK.fbVrR/bpIqNJ5ianF.o./n25XVfn6oAPaUvHe.Csk4zRfsYPi"),
                Arguments.of(
                        hex("ffa33334ffffffa3333435"), "$2a$05$/OK.fbVrR/bpIqNJ5ianF.ZC1JEJ8Z4gPfpe1JOr/oyPXTWl9EFd."),
                Arguments.of(hex("ffa3333435"), "$2y$05$/OK.fbVrR/bpIqNJ5ianF.nRht2l/HRhr6zmCp9vYUvvsqynflf9e"),
                Arguments.of(hex("ffa3333435"), "$2a$05$/OK.fbVrR/bpIqNJ5ianF.nRht2l/HRhr6zmCp9vYUvvsqynflf9e"),
                Arguments.of(hex("a36162"), "$2a$05$/OK.fbVrR/bpIqNJ5ianF.6IflQkJytoRVc1yuaNtHfiuq.FRlSIS"),
                Arguments.of(hex("a36162"), "$2y$05$/OK.fbVrR/bpIqNJ5ianF.6IflQkJytoRVc1yuaNtHfiuq.FRlSIS"),
                Arguments.of(
                        concat(hex("aa".repeat(72)), ascii("chars after 72 are ignored as usual")),
                        "$2a$05$/OK.fbVrR/bpIqNJ5ianF.swQOIzjOiJ9GHEPuhEkvqrUyvWhEMx6"),
                Arguments.of(hex("aa55".repeat(36)), "$2a$05$/OK.fbVrR/bpIqNJ5ianF.R9xrDjiycxMbQE2bp.vgqlYpW5wx2yy"),
                Arguments.of(hex("55aaff".repeat(24)), "$2a$05$/OK.fbVrR/bpIqNJ5ianF.9tQZzcJfm3uj2NvJ/n5xkhpqLrMpWCe"),
                Arguments.of(new byte[0], "$2a$05$CCCCCCCCCCCCCCCCCCCCC.7uG0VCzI2bS7j6ymqJi9CdcdxiRTWNy"),
                Arguments.of(ascii("U*U"), "$2y$04$CCCCCCCCCCCCCCCCCCCCC.K7Qr0se1MxuggH4aP4YgB.U2Em1pGSK"));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @ParameterizedTest
    @MethodSource("publishedVectors")
    void verifiesPublishedVectors(byte[] password, String hash) {
        assertThat(Bcrypt.verify(password, ascii(hash))).isTrue();
    }

    @ParameterizedTest
    @MethodSource("publishedVectors")
    void hashReproducesPublishedVectors(byte[] password, String hash) {
        char minor = hash.charAt(2);
        int cost = Integer.parseInt(hash.substring(4, 6));
        byte[] salt = Bcrypt.decodeSalt(hash.substring(7, 29));
        assertThat(Bcrypt.hash(password, minor, cost, salt)).isEqualTo(hash);
    }

    /** Vectors made with PHP 8.4 crypt() at cost 04, each confirmed with password_verify. */
    static Stream<Arguments> keyLengthVectors() {
        String saltA = "$2y$04$mZJBzMV0/venMiraZV4Ni."; // 16 salt bytes a1b2c3...8f90
        String saltB = "$2a$04$..CA.uOD/eaGAOmJB.yMBu"; // 16 salt bytes 00 01 02 ... 0f: four distinct words
        return Stream.of(
                Arguments.of(ascii(BASE.substring(0, 71)), saltA + "bRYyCm4GUSJ80taxab8x2mDxKSk1d6."),
                Arguments.of(ascii(BASE.substring(0, 71)), saltB + "ZKtUnLN3isSoXCF2f5EBdqKpedoa9/O"),
                Arguments.of(ascii(BASE.substring(0, 72)), saltA + "gmIU6deiklP7ifN0lGQc4q7LmubJjMu"),
                Arguments.of(ascii(BASE.substring(0, 72)), saltB + "r9RSvvVA4nHX179Dq8FDJ.5IXY3CsRi"),
                Arguments.of(ascii(BASE.substring(0, 72) + "a"), saltA + "gmIU6deiklP7ifN0lGQc4q7LmubJjMu"),
                Arguments.of(ascii(BASE + "Z".repeat(19)), saltB + "r9RSvvVA4nHX179Dq8FDJ.5IXY3CsRi"),
                // 71 bytes then NUL: the terminator is the 72nd byte, so this is the 71-byte password
                Arguments.of(
                        concat(ascii(BASE.substring(0, 71)), hex("005441494c")),
                        saltA + "bRYyCm4GUSJ80taxab8x2mDxKSk1d6."),
                Arguments.of(
                        concat(ascii(BASE.substring(0, 71)), hex("005441494c")),
                        saltB + "ZKtUnLN3isSoXCF2f5EBdqKpedoa9/O"),
                // embedded NUL: PHP hashes only "ab"
                Arguments.of(hex("6162006364"), saltA + "SQWL1jcHeOfK8smAIIL70acy3PKWYR."),
                Arguments.of(hex("6162006364"), saltB + "1WhiU9zwwBcNtKllBYkJuuz6BA0n.wa"),
                // leading NUL: the empty key
                Arguments.of(hex("00616263"), saltA + "EGBIs1it8ix1VCZMgfaUvPJiZlYkGWi"),
                Arguments.of(hex("00616263"), saltB + "q1wbVgYezarXi1FgOYk1qMNtxVndjJm"));
    }

    @ParameterizedTest
    @MethodSource("keyLengthVectors")
    void keyLengthAndNulVectorsMatchPhp(byte[] password, String hash) {
        assertThat(Bcrypt.verify(password, ascii(hash))).isTrue();
        assertThat(Bcrypt.hash(password, hash.charAt(2), 4, Bcrypt.decodeSalt(hash.substring(7, 29))))
                .isEqualTo(hash);
    }

    @Test
    void wrongPasswordsFail() {
        assertThat(Bcrypt.verify(ascii("password"), ascii(PASSWORD_HASH))).isTrue();
        assertThat(Bcrypt.verify(ascii("passw0rd"), ascii(PASSWORD_HASH))).isFalse();
        assertThat(Bcrypt.verify(ascii(" password "), ascii(PASSWORD_HASH))).isFalse();
    }

    @Test
    void passwordStopsAtTheFirstNulByte() {
        // bcrypt of "a" (reference vector 12.2#7); PHP hands the password to crypt() as a C string
        String hashOfA = "$2y$05$2qAuGekcbESQWcvdM6anROQm6sSjDpF5zbSmKgHpAQCZZAnhZJP0m";
        assertThat(Bcrypt.verify(new byte[] {'a', 0, 'b'}, ascii(hashOfA))).isTrue();
        assertThat(Bcrypt.verify(new byte[] {'a', 0, 'z', 'z', 'z'}, ascii(hashOfA)))
                .isTrue();
        assertThat(Bcrypt.verify(ascii("ab"), ascii(hashOfA))).isFalse();
    }

    @Test
    void onlyTheFirst72BytesCount() {
        String hash = "$2y$05$XRBucXr41JCjy4pbu9a.peBYvzeLR6y4r84yA2zWgziVC0xtk7ana"; // reference 12.2#4 and #8
        assertThat(Bcrypt.verify(ascii("a".repeat(72) + "TAIL-" + "b".repeat(23)), ascii(hash)))
                .isTrue();
        assertThat(Bcrypt.verify(ascii("a".repeat(72) + "DIFF-" + "b".repeat(23)), ascii(hash)))
                .isTrue();
        assertThat(Bcrypt.verify(ascii("a".repeat(71)), ascii(hash))).isFalse();
    }

    @Test
    void twoAAppliesItsCountermeasureOnlyToSomeEightBitKeys() {
        // PHP crypt() gives the CE5e… digest for $2y$ and $2b$ but nqd1… for $2a$ (vectors above)
        assertThat(Bcrypt.verify(hex("ffffa3"), ascii("$2a$05$/OK.fbVrR/bpIqNJ5ianF.CE5elHaaO4EbggVDjb8P19RukzXSM3e")))
                .isFalse();
        // ASCII keys: the three ids give the same digest
        byte[] salt = Bcrypt.decodeSalt("CCCCCCCCCCCCCCCCCCCCC.");
        String a = Bcrypt.hash(ascii("U*U"), 'a', 4, salt);
        assertThat(Bcrypt.hash(ascii("U*U"), 'b', 4, salt).substring(3)).isEqualTo(a.substring(3));
        assertThat(Bcrypt.hash(ascii("U*U"), 'y', 4, salt).substring(3)).isEqualTo(a.substring(3));
    }

    @Test
    void twoXIsRefused() {
        // PHP verifies this crypt_blowfish vector; this library does not support $2x$ (README D1)
        assertThat(Bcrypt.verify(hex("a3"), ascii("$2x$05$/OK.fbVrR/bpIqNJ5ianF.CE5elHaaO4EbggVDjb8P19RukzXSM3e")))
                .isFalse();
        assertThatThrownBy(() -> Bcrypt.hash(hex("a3"), 'x', 5, new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void costMustBeTwoDigitsFrom04To31() {
        String afterCost = PASSWORD_HASH.substring(6);
        assertThat(Bcrypt.verify(ascii("password"), ascii("$2y$03" + afterCost)))
                .isFalse();
        assertThat(Bcrypt.verify(ascii("password"), ascii("$2y$32" + afterCost)))
                .isFalse();
        assertThat(Bcrypt.verify(ascii("password"), ascii("$2y$x5" + afterCost)))
                .isFalse();
        assertThat(Bcrypt.verify(ascii("password"), ascii("$2y$5$" + PASSWORD_HASH.substring(7))))
                .isFalse();
    }

    @Test
    void costsAbove16AreRefusedWithoutComputing() {
        // well-formed; PHP would run 2^17 or more rounds (README D3)
        String afterCost = PASSWORD_HASH.substring(6);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(Bcrypt.verify(ascii("password"), ascii("$2y$17" + afterCost)))
                    .isFalse();
            assertThat(Bcrypt.verify(ascii("password"), ascii("$2y$31" + afterCost)))
                    .isFalse();
        });
    }

    @Test
    void saltMustBeCanonical() {
        // 'v' in the 22nd salt position decodes to the same 16 bytes as 'u'; PHP rebuilds the string with 'u' and
        // rejects the stored one (confirmed with password_verify), and so does this library
        String nonCanonical = PASSWORD_HASH.substring(0, 28) + "v" + PASSWORD_HASH.substring(29);
        assertThat(Bcrypt.verify(ascii("password"), ascii(nonCanonical))).isFalse();
    }

    @Test
    void malformedHashesFail() {
        String[] malformed = {
            PASSWORD_HASH.substring(0, 59),
            PASSWORD_HASH + "x",
            PASSWORD_HASH.substring(0, 10) + "!" + PASSWORD_HASH.substring(11),
            PASSWORD_HASH.substring(0, 40) + "!" + PASSWORD_HASH.substring(41),
            "$2Y" + PASSWORD_HASH.substring(3),
            "$3y" + PASSWORD_HASH.substring(3),
            "$2y$05x" + PASSWORD_HASH.substring(7),
            "x" + PASSWORD_HASH.substring(1)
        };
        for (String hash : malformed) {
            assertThat(Bcrypt.verify(ascii("password"), ascii(hash))).as(hash).isFalse();
        }
    }

    @Test
    void verifiesTheWordPressPrehashString() {
        // reference 12.1#17: a $wp hash without its prefix verifies the Base64 HMAC-SHA-384 pre-hash itself
        assertThat(Bcrypt.verify(
                        ascii("ZEIBxmCIjkyF8MNbMmsrJOPmp2/ioyAvd90BaIOY1lr6z3gfcWZusfjOv0kWasvS"),
                        ascii("$2y$05$5KLnDnATV5NLkbHSOiGp7OBSSVzspqjDios4TlIEUuIwunPkuBtvi")))
                .isTrue();
    }

    @Test
    void hashRejectsBadArguments() {
        assertThatThrownBy(() -> Bcrypt.hash(ascii("x"), 'y', 3, new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Bcrypt.hash(ascii("x"), 'y', 32, new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Bcrypt.hash(ascii("x"), 'y', 4, new byte[15]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Bcrypt.hash(ascii("x"), 'c', 4, new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeSaltRejectsCharactersOutsideTheAlphabet() {
        assertThat(Bcrypt.decodeSalt("CCCCCCCCCCCCCCCCCCCCC.")).hasSize(16);
        assertThat(Bcrypt.decodeSalt("CCCCCCCCCCCCCCCCCCCCC!")).isNull();
        assertThatThrownBy(() -> Bcrypt.decodeSalt("CCC")).isInstanceOf(IllegalArgumentException.class);
    }
}
