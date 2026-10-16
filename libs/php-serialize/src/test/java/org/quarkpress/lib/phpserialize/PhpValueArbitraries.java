package org.quarkpress.lib.phpserialize;

import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

/** Generators of PHP values shared by the property tests and the parity test. */
final class PhpValueArbitraries {

    private PhpValueArbitraries() {}

    /** Any value the writer produces, without back-references: random ones would point nowhere. */
    static Arbitrary<PhpValue> values() {
        return Arbitraries.lazyOf(
                () -> Arbitraries.just(PhpNull.INSTANCE),
                () -> Arbitraries.of(PhpBool.TRUE, PhpBool.FALSE),
                () -> Arbitraries.longs().map(PhpInt::new),
                () -> Arbitraries.doubles().map(PhpFloat::new),
                () -> Arbitraries.bytes().array(byte[].class).ofMaxSize(20).map(PhpString::ofBytes),
                () -> Arbitraries.strings().ofMaxLength(10).map(PhpString::of),
                PhpValueArbitraries::arrays,
                () -> arrays().map(a -> new PhpObject("Foo", a)),
                () -> Arbitraries.bytes().array(byte[].class).ofMaxSize(10).map(p -> new PhpCustomObject("Bar", p)),
                () -> Arbitraries.strings()
                        .alpha()
                        .ofMinLength(1)
                        .ofMaxLength(5)
                        .map(c -> new PhpEnum("Suit", c)));
    }

    static Arbitrary<PhpArray> arrays() {
        Arbitrary<PhpArray.Key> keys = Arbitraries.oneOf(
                Arbitraries.longs().map(PhpArray.Key::of),
                Arbitraries.strings().ofMaxLength(6).map(PhpArray.Key::of));
        return Arbitraries.maps(keys, values()).ofMaxSize(5).map(m -> {
            PhpArray.Builder b = PhpArray.builder();
            for (Map.Entry<PhpArray.Key, PhpValue> e : m.entrySet()) {
                b.put(e.getKey(), e.getValue());
            }
            return b.build();
        });
    }

    /** Two-element arrays whose second element refers to the first (slot 2): R: to any value, r: to an object. */
    static Arbitrary<PhpArray> arraysWithBackReferences() {
        Arbitrary<PhpArray> toValue =
                values().map(v -> PhpArray.of(v, new PhpReference(2, PhpReference.Kind.REFERENCE)));
        Arbitrary<PhpArray> toObject =
                arrays().map(a -> PhpArray.of(new PhpObject("Foo", a), new PhpReference(2, PhpReference.Kind.OBJECT)));
        return Arbitraries.oneOf(toValue, toObject);
    }
}
