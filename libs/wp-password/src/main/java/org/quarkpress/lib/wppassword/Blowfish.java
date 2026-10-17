package org.quarkpress.lib.wppassword;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Blowfish cipher state: an 18-word P-array and four 256-word S-boxes, initialised from the binary digits of pi as
 * the cipher's specification prescribes. Holds only what bcrypt needs: block encryption and key expansion. Not
 * thread-safe; every bcrypt computation makes its own instance.
 */
final class Blowfish {

    private static final int P_WORDS = 18;
    private static final int S_WORDS = 4 * 256;
    private static final int[] PI_WORDS = piFractionWords(P_WORDS + S_WORDS);

    private final int[] p = Arrays.copyOfRange(PI_WORDS, 0, P_WORDS);
    private final int[] s = Arrays.copyOfRange(PI_WORDS, P_WORDS, P_WORDS + S_WORDS);

    /**
     * The first {@code count} 32-bit words of the fractional part of pi (0x243f6a88, 0x85a308d3, …), from Machin's
     * formula pi = 16·atan(1/5) − 4·atan(1/239) in fixed point with 64 guard bits.
     */
    static int[] piFractionWords(int count) {
        int guardBits = 64;
        BigInteger one = BigInteger.ONE.shiftLeft(32 * count + guardBits);
        BigInteger pi = arctanOfInverse(5, one)
                .shiftLeft(4)
                .subtract(arctanOfInverse(239, one).shiftLeft(2));
        BigInteger fraction = pi.subtract(one.multiply(BigInteger.valueOf(3))).shiftRight(guardBits);
        int[] words = new int[count];
        for (int i = count - 1; i >= 0; i--) {
            words[i] = fraction.intValue();
            fraction = fraction.shiftRight(32);
        }
        return words;
    }

    /** {@code one}·atan(1/x) from the alternating series x⁻¹ − x⁻³/3 + x⁻⁵/5 − …, each term truncated. */
    private static BigInteger arctanOfInverse(int x, BigInteger one) {
        BigInteger xSquared = BigInteger.valueOf((long) x * x);
        BigInteger power = one.divide(BigInteger.valueOf(x));
        BigInteger sum = power;
        boolean subtract = true;
        for (long n = 3; power.signum() != 0; n += 2) {
            power = power.divide(xSquared);
            BigInteger term = power.divide(BigInteger.valueOf(n));
            sum = subtract ? sum.subtract(term) : sum.add(term);
            subtract = !subtract;
        }
        return sum;
    }

    /** Encrypts the 64-bit block {@code block[at]} (left half), {@code block[at + 1]} (right half) in place. */
    void encrypt(int[] block, int at) {
        int left = block[at] ^ p[0];
        int right = block[at + 1];
        for (int i = 1; i <= 16; i += 2) {
            right ^= feistel(left) ^ p[i];
            left ^= feistel(right) ^ p[i + 1];
        }
        block[at] = right ^ p[17];
        block[at + 1] = left;
    }

    /**
     * XORs the 18 {@code keyWords} into the P-array, then replaces every P and S word, in order, with successive
     * encryptions of a running block that starts at zero. With a {@code salt} (four words), two salt words are XORed
     * into the block before each encryption, cycling through the salt; with {@code null} this is the plain Blowfish
     * key schedule.
     */
    void expand(int[] keyWords, int[] salt) {
        for (int i = 0; i < P_WORDS; i++) {
            p[i] ^= keyWords[i];
        }
        int[] block = new int[2];
        int next = 0;
        for (int i = 0; i < P_WORDS; i += 2) {
            next = mixSalt(block, salt, next);
            encrypt(block, 0);
            p[i] = block[0];
            p[i + 1] = block[1];
        }
        for (int i = 0; i < S_WORDS; i += 2) {
            next = mixSalt(block, salt, next);
            encrypt(block, 0);
            s[i] = block[0];
            s[i + 1] = block[1];
        }
    }

    private static int mixSalt(int[] block, int[] salt, int next) {
        if (salt == null) {
            return next;
        }
        block[0] ^= salt[next];
        block[1] ^= salt[next + 1];
        return (next + 2) & 3;
    }

    private int feistel(int x) {
        return ((s[x >>> 24] + s[256 | ((x >>> 16) & 0xff)]) ^ s[512 | ((x >>> 8) & 0xff)]) + s[768 | (x & 0xff)];
    }
}
