package org.quarkpress.testing.gen;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Runs a property on generated values. Runs are deterministic: the seed is the system property
 * {@value #SEED_PROPERTY} when set, else derived from the property name. There is no shrinking; a failure reports the
 * seed, the try and the input.
 */
public final class Properties {

    public static final String SEED_PROPERTY = "quarkpressGenSeed";

    private static final int MAX_INPUT_CHARS = 2000;

    private Properties() {}

    /** Checks {@code property} on {@code tries} values, seeded from {@value #SEED_PROPERTY} or from {@code name}. */
    public static <T> void check(String name, int tries, Gen<T> gen, Consumer<? super T> property) {
        String override = System.getProperty(SEED_PROPERTY);
        long seed;
        if (override == null || override.isBlank()) {
            seed = seedOf(name);
        } else {
            try {
                seed = Long.parseLong(override.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Property " + name + ": -D" + SEED_PROPERTY + "=" + override + " is not a decimal long", e);
            }
        }
        check(name, tries, seed, gen, property);
    }

    /**
     * Checks {@code property} on {@code tries} values drawn with {@code seed}; the first failure becomes an
     * AssertionError naming the property, the seed, the try and the input.
     */
    public static <T> void check(String name, int tries, long seed, Gen<T> gen, Consumer<? super T> property) {
        if (tries <= 0) {
            throw new IllegalArgumentException("tries must be positive: " + tries);
        }
        RandomGenerator random = random(seed);
        for (int i = 1; i <= tries; i++) {
            boolean generated = false;
            T value = null;
            try {
                value = gen.next(random);
                generated = true;
                property.accept(value);
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                String input = generated ? describe(value) : "(the generator failed)";
                throw new AssertionError(
                        "Property " + name + " failed on try " + i + " of " + tries + " with seed " + seed
                                + " (reproduce with -D" + SEED_PROPERTY + "=" + seed + ")\ninput: " + input
                                + "\ncause: " + failure,
                        failure);
            }
        }
    }

    /** A seed derived from text: the first eight bytes of the SHA-256 of its UTF-8 encoding, big-endian. */
    public static long seedOf(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    /** The random source used for {@code seed}; the same seed gives the same sequence on every run. */
    public static RandomGenerator random(long seed) {
        return new SplittableRandom(seed);
    }

    private static String describe(Object value) {
        String text = switch (value) {
            case byte[] bytes -> Arrays.toString(bytes);
            case Object[] array -> Arrays.deepToString(array);
            case null -> "null";
            default -> String.valueOf(value);
        };
        return text.length() <= MAX_INPUT_CHARS
                ? text
                : text.substring(0, MAX_INPUT_CHARS) + "... (" + text.length() + " chars)";
    }
}
