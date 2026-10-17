package org.quarkpress.lib.wppassword;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class BlowfishTest {

    @Test
    void initialStateIsTheBinaryFractionOfPi() throws Exception {
        int[] words = Blowfish.piFractionWords(18 + 1024);
        assertThat(words[0]).isEqualTo(0x243f6a88); // P[0]
        assertThat(words[1]).isEqualTo(0x85a308d3);
        assertThat(words[17]).isEqualTo(0x8979fb1b); // P[17]
        assertThat(words[18]).isEqualTo(0xd1310ba6); // S0[0]
        assertThat(words[273]).isEqualTo(0x6e85076a); // S0[255]
        assertThat(words[274]).isEqualTo(0x4b7a70e9); // S1[0]
        assertThat(words[529]).isEqualTo(0xdb83adf7); // S1[255]
        assertThat(words[530]).isEqualTo(0xe93d5a68); // S2[0]
        assertThat(words[785]).isEqualTo(0x406000e0); // S2[255]
        assertThat(words[786]).isEqualTo(0x3a39ce37); // S3[0]
        assertThat(words[1041]).isEqualTo(0x3ac372e6); // S3[255]
        ByteBuffer all = ByteBuffer.allocate(4 * words.length);
        for (int word : words) {
            all.putInt(word);
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(all.array())))
                .isEqualTo("b5643208907b11b20e499a42187dc921f9579d28dadfccbe69a5ce232a55952f");
    }

    @Test
    void encryptsPublishedEcbVectors() {
        assertThat(ecb("0000000000000000", "0000000000000000")).isEqualTo("4ef997456198dd78");
        assertThat(ecb("ffffffffffffffff", "ffffffffffffffff")).isEqualTo("51866fd5b85ecb8a");
        assertThat(ecb("3000000000000000", "1000000000000001")).isEqualTo("7d856f9a613063f2");
        assertThat(ecb("0123456789abcdef", "1111111111111111")).isEqualTo("61f9c3802281b096");
        assertThat(ecb("fedcba9876543210", "0123456789abcdef")).isEqualTo("0aceab0fc6a0a28d");
    }

    /** Plain Blowfish: the key bytes repeated (no terminator) into 18 words, expanded without salt, one block. */
    private static String ecb(String keyHex, String plainHex) {
        byte[] key = HexFormat.of().parseHex(keyHex);
        int[] keyWords = new int[18];
        int position = 0;
        for (int i = 0; i < 18; i++) {
            for (int j = 0; j < 4; j++) {
                keyWords[i] = (keyWords[i] << 8) | (key[position++ % key.length] & 0xff);
            }
        }
        Blowfish cipher = new Blowfish();
        cipher.expand(keyWords, null);
        ByteBuffer plain = ByteBuffer.wrap(HexFormat.of().parseHex(plainHex));
        int[] block = {plain.getInt(0), plain.getInt(4)};
        cipher.encrypt(block, 0);
        return HexFormat.of()
                .formatHex(
                        ByteBuffer.allocate(8).putInt(block[0]).putInt(block[1]).array());
    }
}
