package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class RoundTripPropertyTest {

    @Property(tries = 2000)
    void serializeThenParseIsIdentity(@ForAll("values") PhpValue v) {
        byte[] bytes = PhpSerialize.serialize(v);
        PhpValue back = PhpSerialize.parse(bytes);
        assertThat(back).isEqualTo(v);
        assertThat(PhpSerialize.serialize(back)).isEqualTo(bytes);
    }

    @Property(tries = 2000)
    void repairModeAcceptsEveryWellFormedDocument(@ForAll("values") PhpValue v) {
        byte[] bytes = PhpSerialize.serialize(v);
        assertThat(PhpSerialize.parse(bytes, ParseOptions.DEFAULTS.withRepairLengths(true)))
                .isEqualTo(v);
    }

    @Property(tries = 5000)
    void parserNeverThrowsAnythingButPhpSerializeException(@ForAll byte[] junk) {
        try {
            PhpSerialize.parse(junk);
        } catch (PhpSerializeException expected) {
            // acceptable outcome for junk input
        }
    }

    @Property(tries = 3000)
    void mutatedValidInputNeverCrashes(
            @ForAll("values") PhpValue v, @ForAll @IntRange(min = 0, max = 10_000) int at, @ForAll byte b) {
        byte[] bytes = PhpSerialize.serialize(v);
        bytes[at % bytes.length] = b;
        try {
            PhpSerialize.parse(bytes, ParseOptions.DEFAULTS.withRepairLengths(true));
        } catch (PhpSerializeException expected) {
            // acceptable outcome for corrupted input
        }
    }

    @Property(tries = 1000)
    void conversionsNeverThrow(@ForAll("values") PhpValue v) {
        v.toJavaObject();
        assertThat(v.toJson()).isNotEmpty();
    }

    @Provide
    Arbitrary<PhpValue> values() {
        return Arbitraries.lazyOf(
                () -> Arbitraries.just(PhpNull.INSTANCE),
                () -> Arbitraries.of(PhpBool.TRUE, PhpBool.FALSE),
                () -> Arbitraries.longs().map(PhpInt::new),
                () -> Arbitraries.doubles().map(PhpFloat::new),
                () -> Arbitraries.bytes().array(byte[].class).ofMaxSize(20).map(PhpString::ofBytes),
                () -> Arbitraries.strings().ofMaxLength(10).map(PhpString::of),
                this::arrays,
                () -> arrays().map(a -> new PhpObject("Foo", a)),
                () -> Arbitraries.bytes().array(byte[].class).ofMaxSize(10).map(p -> new PhpCustomObject("Bar", p)),
                () -> Arbitraries.longs().between(1, 50).map(i -> new PhpReference(i, PhpReference.Kind.OBJECT)),
                () -> Arbitraries.longs().between(1, 50).map(i -> new PhpReference(i, PhpReference.Kind.REFERENCE)),
                () -> Arbitraries.strings()
                        .alpha()
                        .ofMinLength(1)
                        .ofMaxLength(5)
                        .map(c -> new PhpEnum("Suit", c)));
    }

    Arbitrary<PhpArray> arrays() {
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
}
