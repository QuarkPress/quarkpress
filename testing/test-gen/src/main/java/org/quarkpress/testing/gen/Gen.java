package org.quarkpress.testing.gen;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * A generator of test values. Every value is drawn from the random source passed in, so a seeded source gives the
 * same values on every run.
 */
@FunctionalInterface
public interface Gen<T> {

    T next(RandomGenerator random);

    default <R> Gen<R> map(Function<? super T, ? extends R> f) {
        Objects.requireNonNull(f, "f");
        return random -> f.apply(next(random));
    }

    default <R> Gen<R> flatMap(Function<? super T, ? extends Gen<? extends R>> f) {
        Objects.requireNonNull(f, "f");
        return random -> f.apply(next(random)).next(random);
    }

    /** Keeps only values that pass {@code keep}; throws IllegalStateException after 1000 rejections in a row. */
    default Gen<T> filter(Predicate<? super T> keep) {
        Objects.requireNonNull(keep, "keep");
        return random -> {
            for (int i = 0; i < 1000; i++) {
                T value = next(random);
                if (keep.test(value)) {
                    return value;
                }
            }
            throw new IllegalStateException("Gen.filter rejected 1000 generated values in a row");
        };
    }
}
