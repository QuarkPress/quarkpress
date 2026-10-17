package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class PasswordPropertiesTest {

    private record Pair(byte[] password, String hash) {}

    private static Gen<String> anyHash() {
        return Gens.<String>frequency(
                Gens.weighted(3, PasswordGens.garbageHashes()),
                Gens.weighted(1, Gens.strings(Gens.unicodeChars(), 0, 100)));
    }

    private static String phpass(byte[] password, String salt) {
        return new String(Phpass.compute(password, ("$P$7" + salt).getBytes(US_ASCII)), US_ASCII);
    }

    @Test
    void arbitraryInputIsRejectedWithoutThrowing() {
        // The hashes are look-alikes or random text, never the hash of the password beside them, so every
        // verifier answers false; detect is total and stable; needsRehash holds for all of them.
        Gen<byte[]> passwords = PasswordGens.passwords();
        Gen<String> hashes = anyHash();
        Gen<Pair> pairs = random -> new Pair(passwords.next(random), hashes.next(random));
        Properties.check("PasswordPropertiesTest.arbitraryInputIsRejectedWithoutThrowing", 3000, pairs, p -> {
            String text = new String(p.password(), UTF_8);
            assertThat(WpPasswords.check(p.password(), p.hash())).as(p.hash()).isFalse();
            assertThat(WpPasswords.check(text, p.hash())).as(p.hash()).isFalse();
            assertThat(WpPasswords.verify(text, p.hash())).as(p.hash()).isFalse();
            assertThat(WpPasswords.checkFastHash(text, p.hash())).as(p.hash()).isFalse();
            assertThat(WpPasswords.needsRehash(p.hash())).as(p.hash()).isTrue();
            HashType type = WpPasswords.detect(p.hash());
            assertThat(type).isNotNull().isEqualTo(WpPasswords.detect(p.hash()));
            if (type == HashType.PHPASS_PHPBB || type == HashType.UNKNOWN) {
                assertThat(WpPasswords.check(p.password(), p.hash())).isFalse();
            }
        });
    }

    @Test
    void phpassHashesRoundTrip() {
        Gen<byte[]> passwords = PasswordGens.passwords();
        Gen<String> salts = Gens.strings(Phpass.ALPHABET, 8, 8);
        Gen<Pair> pairs = random -> {
            byte[] password = passwords.next(random);
            return new Pair(password, phpass(password, salts.next(random)));
        };
        Properties.check("PasswordPropertiesTest.phpassHashesRoundTrip", 300, pairs, p -> {
            assertThat(WpPasswords.check(p.password(), p.hash()))
                    .isEqualTo(p.password().length <= WpPasswords.MAX_PASSWORD_BYTES);
            assertThat(WpPasswords.needsRehash(p.hash())).isTrue();
            String md5 = new String(PhpBytes.md5Hex(p.password()), US_ASCII);
            assertThat(WpPasswords.check(p.password(), md5)).isTrue();
            assertThat(WpPasswords.needsRehash(md5)).isTrue();
        });
    }

    @Test
    void wpBcryptHashesRoundTripAtCost4() {
        Gen<byte[]> passwords = PasswordGens.passwords();
        Gen<byte[]> salts = Gens.bytes(16, 16);
        Gen<Pair> pairs = random -> {
            byte[] password = passwords.next(random);
            return new Pair(password, WpPasswords.wpBcryptHash(password, 4, salts.next(random)));
        };
        Properties.check("PasswordPropertiesTest.wpBcryptHashesRoundTripAtCost4", 60, pairs, p -> {
            assertThat(WpPasswords.check(p.password(), p.hash()))
                    .isEqualTo(p.password().length <= WpPasswords.MAX_PASSWORD_BYTES);
            assertThat(WpPasswords.needsRehash(p.hash())).isTrue();
            assertThat(WpPasswords.detect(p.hash())).isEqualTo(HashType.WP_BCRYPT);
        });
    }

    @Test
    void mutatedHashesNeverVerify() {
        Gen<byte[]> passwords = PasswordGens.passwords().filter(p -> p.length <= WpPasswords.MAX_PASSWORD_BYTES);
        Gen<byte[]> salts = Gens.bytes(16, 16);
        Gen<String> phpassSalts = Gens.strings(Phpass.ALPHABET, 8, 8);
        Gen<Integer> kinds = Gens.ints(0, 3);
        Gen<Integer> picks = Gens.ints(0, 100_000);
        Gen<Pair> mutated = random -> {
            byte[] password = passwords.next(random);
            int pick = picks.next(random);
            return switch (kinds.next(random)) {
                case 0 -> {
                    // MD5: any of the 32 digits
                    String hash = new String(PhpBytes.md5Hex(password), US_ASCII);
                    yield new Pair(
                            password, PasswordGens.replaceChar(hash, pick % 32, PasswordGens.HEX_ALPHABET, pick / 32));
                }
                case 1 -> {
                    // phpass: salt or digest, never the count character
                    String hash = phpass(password, phpassSalts.next(random));
                    yield new Pair(password, PasswordGens.replaceChar(hash, 4 + pick % 30, Phpass.ALPHABET, pick / 30));
                }
                case 2 -> {
                    // $wp bcrypt at cost 4: salt or digest, never the id or the cost
                    String hash = WpPasswords.wpBcryptHash(password, 4, salts.next(random));
                    yield new Pair(
                            password,
                            PasswordGens.replaceChar(hash, 10 + pick % 53, PasswordGens.BCRYPT_ALPHABET, pick / 53));
                }
                default -> {
                    String hash = phpass(password, phpassSalts.next(random));
                    yield new Pair(password, hash.substring(0, hash.length() - 1));
                }
            };
        };
        Properties.check(
                "PasswordPropertiesTest.mutatedHashesNeverVerify",
                400,
                mutated,
                p -> assertThat(WpPasswords.check(p.password(), p.hash()))
                        .as(p.hash())
                        .isFalse());
    }
}
