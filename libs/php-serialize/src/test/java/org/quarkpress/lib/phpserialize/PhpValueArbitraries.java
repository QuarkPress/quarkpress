package org.quarkpress.lib.phpserialize;

import java.util.LinkedHashMap;
import java.util.Map;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;

/** Generators of PHP values shared by the property tests and the parity test. */
final class PhpValueArbitraries {

    /** How deeply arrays and objects nest at most. */
    private static final int MAX_DEPTH = 4;

    private static final String ALPHA = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private PhpValueArbitraries() {}

    /** Any value the writer produces, without back-references: random ones would point nowhere. */
    static Gen<PhpValue> values() {
        Gen<PhpValue> scalar = Gens.oneOf(
                Gens.constant(PhpNull.INSTANCE),
                Gens.elements(PhpBool.TRUE, PhpBool.FALSE),
                Gens.longs(Long.MIN_VALUE, Long.MAX_VALUE).map(PhpInt::new),
                Gens.doubles().map(PhpFloat::new),
                Gens.bytes(0, 20).map(PhpString::ofBytes),
                Gens.strings(Gens.unicodeChars(), 0, 10).map(PhpString::of),
                Gens.bytes(0, 10).map(p -> new PhpCustomObject("Bar", p)),
                Gens.strings(ALPHA, 1, 5).map(c -> new PhpEnum("Suit", c)));
        // scalars, arrays and objects in the ratio 8 : 1 : 1
        return Gens.recursive(
                scalar,
                inner -> Gens.frequency(
                        Gens.weighted(8, scalar),
                        Gens.weighted(1, arraysOf(inner)),
                        Gens.weighted(1, arraysOf(inner).map(a -> new PhpObject("Foo", a)))),
                MAX_DEPTH);
    }

    static Gen<PhpArray> arrays() {
        return arraysOf(values());
    }

    /** Arrays of up to five entries with distinct integer or string keys. */
    private static Gen<PhpArray> arraysOf(Gen<? extends PhpValue> values) {
        Gen<PhpArray.Key> keys = Gens.oneOf(
                Gens.longs(Long.MIN_VALUE, Long.MAX_VALUE).map(PhpArray.Key::of),
                Gens.strings(Gens.unicodeChars(), 0, 6).map(PhpArray.Key::of));
        Gen<Map.Entry<PhpArray.Key, PhpValue>> entries = random -> Map.entry(keys.next(random), values.next(random));
        return Gens.lists(entries, 0, 5).map(list -> {
            Map<PhpArray.Key, PhpValue> distinct = new LinkedHashMap<>();
            list.forEach(e -> distinct.putIfAbsent(e.getKey(), e.getValue()));
            PhpArray.Builder b = PhpArray.builder();
            distinct.forEach(b::put);
            return b.build();
        });
    }

    /** Two-element arrays whose second element refers to the first (slot 2): R: to any value, r: to an object. */
    static Gen<PhpArray> arraysWithBackReferences() {
        Gen<PhpArray> toValue = values().map(v -> PhpArray.of(v, new PhpReference(2, PhpReference.Kind.REFERENCE)));
        Gen<PhpArray> toObject =
                arrays().map(a -> PhpArray.of(new PhpObject("Foo", a), new PhpReference(2, PhpReference.Kind.OBJECT)));
        return Gens.oneOf(toValue, toObject);
    }
}
