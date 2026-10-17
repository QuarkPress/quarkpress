package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class Blake2bTest {

    private static final byte[] NO_KEY = new byte[0];

    private static byte[] hex(String text) {
        return HexFormat.of().parseHex(text);
    }

    /** 0x00, 0x01, … : the key and message pattern of the BLAKE2 reference test vectors. */
    private static byte[] counting(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) i;
        }
        return out;
    }

    @Test
    void rfc7693AppendixAAbc() {
        assertThat(Blake2b.hash(NO_KEY, "abc".getBytes(US_ASCII), 64))
                .isEqualTo(hex("ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1"
                        + "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923"));
    }

    @Test
    void emptyMessageWithoutKey() {
        assertThat(Blake2b.hash(NO_KEY, new byte[0], 64))
                .isEqualTo(hex("786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419"
                        + "d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce"));
    }

    @Test
    void keyedReferenceVectors() {
        byte[] key = counting(64);
        assertThat(Blake2b.hash(key, new byte[0], 64))
                .isEqualTo(hex("10ebb67700b1868efb4417987acf4690ae9d972fb7a590c2f02871799aaa4786"
                        + "b5e996e8f0f4eb981fc214b005f42d2ff4233499391653df7aefcbc13fc51568"));
        assertThat(Blake2b.hash(key, counting(1), 64))
                .isEqualTo(hex("961f6dd1e4dd30f63901690c512e78e4b45e4742ed197c3c5e45c549fd25f2e4"
                        + "187b0bc9fe30492b16b0d0bc4ef9b0f34c7003fac09a5ef1532e69430234cebd"));
        assertThat(Blake2b.hash(key, counting(128), 64))
                .isEqualTo(hex("72065ee4dd91c2d8509fa1fc28a37c7fc9fa7d5b3f8ad3d0d7a25626b57b1b44"
                        + "788d4caf806290425f9890a3a2a35a905ab4b37acfd0da6e4517b2525c9651e4"));
        assertThat(Blake2b.hash(key, counting(255), 64))
                .isEqualTo(hex("142709d62e28fcccd0af97fad0f8465b971e82201dc51070faa0372aa43e9248"
                        + "4be1c1e73ba10906d5d1853db6a4106e0a7bf9800d373d6dee2d46d62ef2a461"));
    }

    @Test
    void rfc7693AppendixESelfTest() {
        // Digest lengths 20..64 and message lengths 0..1024, unkeyed and keyed, all hashed into one 32-byte digest.
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (int digestBytes : new int[] {20, 32, 48, 64}) {
            for (int messageBytes : new int[] {0, 3, 128, 129, 255, 1024}) {
                byte[] message = selfTestSequence(messageBytes, messageBytes);
                all.writeBytes(Blake2b.hash(NO_KEY, message, digestBytes));
                all.writeBytes(Blake2b.hash(selfTestSequence(digestBytes, digestBytes), message, digestBytes));
            }
        }
        assertThat(Blake2b.hash(NO_KEY, all.toByteArray(), 32))
                .isEqualTo(hex("c23a7800d98123bd10f506c61e29da5603d763b8bbad2e737f5e765a7bccd475"));
    }

    /** RFC 7693's deterministic test input: an additive sequence seeded with {@code seed}, top byte of each step. */
    private static byte[] selfTestSequence(int length, int seed) {
        int a = 0xDEAD4BAD * seed;
        int b = 1;
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            int t = a + b;
            a = b;
            b = t;
            out[i] = (byte) (t >>> 24);
        }
        return out;
    }

    @Test
    void wordPressFastHashParameters() {
        // key "wp_fast_hash_6.8+", 30-byte digest: the raw bytes behind
        // "$generic$F6W18u0QEytvRVb59DemVWRxyTxU6vg_VK0s_f-h"
        assertThat(Blake2b.hash(
                        "wp_fast_hash_6.8+".getBytes(US_ASCII), "abcdEFGH1234ijklMNOP5678".getBytes(US_ASCII), 30))
                .isEqualTo(hex("17a5b5f2ed10132b6f4556f9f437a6556471c93c54eaf83f54ad2cfdffa1"));
    }

    @Test
    void rejectsLengthsOutsideTheSpecification() {
        assertThatThrownBy(() -> Blake2b.hash(NO_KEY, new byte[0], 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Blake2b.hash(NO_KEY, new byte[0], 65)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Blake2b.hash(new byte[65], new byte[0], 32))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
