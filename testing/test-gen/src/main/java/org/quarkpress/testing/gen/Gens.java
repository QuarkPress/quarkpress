package org.quarkpress.testing.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * Basic generators. Numeric generators pick an edge value (bounds, zero, neighbours of the bounds) on about one draw
 * in ten; sizes of strings, byte arrays and lists lean toward small and hit both bounds regularly.
 */
public final class Gens {

    private Gens() {}

    /** A choice for {@link #frequency}: {@code gen} is used with probability proportional to {@code weight}. */
    public record Weighted<T>(int weight, Gen<? extends T> gen) {
        public Weighted {
            if (weight <= 0) {
                throw new IllegalArgumentException("weight must be positive: " + weight);
            }
            Objects.requireNonNull(gen, "gen");
        }
    }

    public static <T> Weighted<T> weighted(int weight, Gen<? extends T> gen) {
        return new Weighted<>(weight, gen);
    }

    public static <T> Gen<T> constant(T value) {
        return random -> value;
    }

    @SafeVarargs
    public static <T> Gen<T> elements(T... values) {
        List<T> list = new ArrayList<>(values.length);
        for (T value : values) {
            list.add(value);
        }
        return elements(list);
    }

    /** One of {@code values}, each equally likely. */
    public static <T> Gen<T> elements(List<T> values) {
        List<T> copy = List.copyOf(values);
        requireNonEmpty(copy.size());
        return random -> copy.get(random.nextInt(copy.size()));
    }

    /** A value of one of {@code gens}, each generator equally likely. */
    @SafeVarargs
    public static <T> Gen<T> oneOf(Gen<? extends T>... gens) {
        List<Gen<? extends T>> copy = new ArrayList<>(gens.length);
        for (Gen<? extends T> gen : gens) {
            copy.add(Objects.requireNonNull(gen, "gen"));
        }
        requireNonEmpty(copy.size());
        return random -> copy.get(random.nextInt(copy.size())).next(random);
    }

    /** A value of one of the weighted generators, chosen with probability proportional to its weight. */
    @SafeVarargs
    public static <T> Gen<T> frequency(Weighted<? extends T>... choices) {
        List<Weighted<? extends T>> copy = new ArrayList<>(choices.length);
        for (Weighted<? extends T> choice : choices) {
            copy.add(Objects.requireNonNull(choice, "choice"));
        }
        requireNonEmpty(copy.size());
        int total = copy.stream().mapToInt(Weighted::weight).sum();
        return random -> {
            int pick = random.nextInt(total);
            for (Weighted<? extends T> choice : copy) {
                pick -= choice.weight();
                if (pick < 0) {
                    return choice.gen().next(random);
                }
            }
            throw new AssertionError("unreachable");
        };
    }

    /** Integers in {@code [min, max]}. */
    public static Gen<Integer> ints(int min, int max) {
        return longs(min, max).map(Long::intValue);
    }

    /**
     * Longs in {@code [min, max]}: an edge value one draw in ten; otherwise, half of the time, a value of a random
     * magnitude (bit length uniform up to that of the bounds, so 1-digit and 18-digit numbers are equally common), else
     * a uniform value.
     */
    public static Gen<Long> longs(long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("min " + min + " > max " + max);
        }
        List<Long> edges = new ArrayList<>(List.of(min, max));
        for (long candidate : new long[] {0, 1, -1}) {
            if (candidate >= min && candidate <= max) {
                edges.add(candidate);
            }
        }
        if (min < max) {
            edges.add(min + 1);
            edges.add(max - 1);
        }
        List<Long> edgeValues = List.copyOf(edges);
        int maxBits = bitLength(Math.max(magnitude(min), magnitude(max)));
        return random -> {
            int roll = random.nextInt(20);
            if (roll < 2) {
                return edgeValues.get(random.nextInt(edgeValues.size()));
            }
            if (roll < 11) {
                for (int attempt = 0; attempt < MAGNITUDE_ATTEMPTS; attempt++) {
                    long candidate = ofRandomMagnitude(random, maxBits);
                    if (candidate >= min && candidate <= max) {
                        return candidate;
                    }
                }
            }
            if (max < Long.MAX_VALUE) {
                return random.nextLong(min, max + 1);
            }
            if (min > Long.MIN_VALUE) {
                return random.nextLong(min - 1, max) + 1;
            }
            return random.nextLong();
        };
    }

    /** How often a magnitude-biased draw is retried before falling back to a uniform one. */
    private static final int MAGNITUDE_ATTEMPTS = 8;

    /** |value|, with Long.MIN_VALUE mapped to Long.MAX_VALUE. */
    private static long magnitude(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private static int bitLength(long nonNegative) {
        return Long.SIZE - Long.numberOfLeadingZeros(nonNegative);
    }

    /** A signed value whose magnitude has a bit length drawn uniformly from {@code [0, maxBits]}. */
    private static long ofRandomMagnitude(RandomGenerator random, int maxBits) {
        int bits = random.nextInt(maxBits + 1);
        if (bits == 0) {
            return 0;
        }
        long value = random.nextLong() >>> (Long.SIZE - bits); // in [0, 2^bits)
        return random.nextBoolean() ? value : -value;
    }

    private static final List<Double> SPECIAL_DOUBLES = List.of(
            0.0,
            -0.0,
            Double.MIN_VALUE,
            -Double.MIN_VALUE,
            Double.MAX_VALUE,
            -Double.MAX_VALUE,
            1.0,
            -1.0,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY);

    /**
     * Any double: special values (zeros, extremes, ±1, NaN, infinities) on about one draw in ten, otherwise either an
     * arbitrary finite bit pattern or a number with at most two decimals below one million in magnitude.
     */
    public static Gen<Double> doubles() {
        return random -> {
            int roll = random.nextInt(20);
            if (roll < 2) {
                return SPECIAL_DOUBLES.get(random.nextInt(SPECIAL_DOUBLES.size()));
            }
            if (roll < 11) {
                double d;
                do {
                    d = Double.longBitsToDouble(random.nextLong());
                } while (!Double.isFinite(d));
                return d;
            }
            return random.nextLong(-100_000_000L, 100_000_001L) / 100.0;
        };
    }

    /**
     * Finite doubles in {@code [min, max]}: the bounds and zero (when in range) one draw in ten; otherwise, half of the
     * time, a short number (an integer of random magnitude divided by 1, 10 or 100, such as 42, 1.5 or -0.25), else a
     * uniform value.
     */
    public static Gen<Double> doubles(double min, double max) {
        if (!(min <= max) || !Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(max - min)) {
            throw new IllegalArgumentException("bad range [" + min + ", " + max + "]");
        }
        List<Double> edges = min <= 0.0 && 0.0 <= max ? List.of(min, max, 0.0) : List.of(min, max);
        // bit length of the largest bound times 100 (capped near 1e18, so at most 60 bits)
        int maxBits = bitLength((long) Math.min(Math.max(Math.abs(min), Math.abs(max)) * 100, 1e18));
        return random -> {
            int roll = random.nextInt(20);
            if (min == max || roll < 2) {
                return edges.get(random.nextInt(edges.size()));
            }
            if (roll < 11) {
                for (int attempt = 0; attempt < MAGNITUDE_ATTEMPTS; attempt++) {
                    double candidate = ofRandomMagnitude(random, maxBits) / SHORT_SCALES[random.nextInt(3)];
                    if (candidate >= min && candidate <= max) {
                        return candidate;
                    }
                }
            }
            return random.nextDouble(min, max);
        };
    }

    private static final double[] SHORT_SCALES = {1.0, 10.0, 100.0};

    public static Gen<Boolean> booleans() {
        return RandomGenerator::nextBoolean;
    }

    /** Characters in {@code [min, max]}, with the same edge bias as {@link #ints}. */
    private static Gen<Character> chars(char min, char max) {
        return ints(min, max).map(i -> (char) i.intValue());
    }

    /**
     * Any UTF-16 code unit that is not a surrogate: printable ASCII half of the time, control characters one draw in
     * six, otherwise anything else in the Basic Multilingual Plane.
     */
    public static Gen<Character> unicodeChars() {
        return frequency(
                weighted(3, chars(' ', '~')),
                weighted(1, chars('\u0000', '\u001f')),
                weighted(2, chars('\u007f', '\uffff').filter(c -> !Character.isSurrogate(c))));
    }

    public static Gen<String> strings(Gen<Character> chars, int minLength, int maxLength) {
        checkSizes(minLength, maxLength);
        return random -> {
            int length = size(random, minLength, maxLength);
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(chars.next(random).charValue());
            }
            return sb.toString();
        };
    }

    /** Strings of characters taken uniformly from {@code alphabet}. */
    public static Gen<String> strings(String alphabet, int minLength, int maxLength) {
        requireNonEmpty(alphabet.length());
        return strings(random -> alphabet.charAt(random.nextInt(alphabet.length())), minLength, maxLength);
    }

    /** Byte arrays of uniformly random bytes. */
    public static Gen<byte[]> bytes(int minLength, int maxLength) {
        checkSizes(minLength, maxLength);
        return random -> {
            byte[] out = new byte[size(random, minLength, maxLength)];
            random.nextBytes(out);
            return out;
        };
    }

    /** Mutable lists of independently drawn elements. */
    public static <T> Gen<List<T>> lists(Gen<T> element, int minSize, int maxSize) {
        checkSizes(minSize, maxSize);
        return random -> {
            int size = size(random, minSize, maxSize);
            List<T> out = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                out.add(element.next(random));
            }
            return out;
        };
    }

    /**
     * A generator for recursive structures, at most {@code maxDepth} levels of {@code branch} deep. {@code branch}
     * receives the generator for one level less and returns the generator for the current level; at depth 0 only
     * {@code leaf} is used. A branch that should also yield leaves mixes {@code leaf} in itself.
     */
    public static <T> Gen<T> recursive(Gen<T> leaf, Function<Gen<T>, Gen<T>> branch, int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must not be negative: " + maxDepth);
        }
        Gen<T> gen = leaf;
        for (int depth = 1; depth <= maxDepth; depth++) {
            gen = branch.apply(gen);
        }
        return gen;
    }

    /** A size in {@code [min, max]}: min and max one draw in twenty each, otherwise leaning toward small sizes. */
    private static int size(RandomGenerator random, int min, int max) {
        if (min == max) {
            return min;
        }
        int roll = random.nextInt(20);
        if (roll == 0) {
            return min;
        }
        if (roll == 1) {
            return max;
        }
        double u = random.nextDouble();
        return min + (int) Math.min(max - min, Math.floor(u * u * (max - min + 1.0)));
    }

    private static void checkSizes(int min, int max) {
        if (min < 0 || min > max) {
            throw new IllegalArgumentException("bad size range [" + min + ", " + max + "]");
        }
    }

    private static void requireNonEmpty(int size) {
        if (size == 0) {
            throw new IllegalArgumentException("nothing to choose from");
        }
    }
}
