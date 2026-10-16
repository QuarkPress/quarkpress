package org.quarkpress.testing.wporacle;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Properties;

/** Draws inputs from a module's generators with a fixed seed, so a parity batch is the same on every run. */
public final class GeneratedCases {

    private GeneratedCases() {}

    /** {@code count} values of {@code gen}, seeded from the text {@code seed}; equal on every run and every JVM. */
    public static <T> List<T> sample(Gen<T> gen, String seed, int count) {
        RandomGenerator random = Properties.random(Properties.seedOf(seed));
        List<T> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(gen.next(random));
        }
        return List.copyOf(out);
    }
}
