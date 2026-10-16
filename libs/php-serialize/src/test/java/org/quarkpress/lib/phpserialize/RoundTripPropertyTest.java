package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;

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
        return PhpValueArbitraries.values();
    }

    @Provide
    Arbitrary<PhpArray> withReferences() {
        return PhpValueArbitraries.arraysWithBackReferences();
    }

    @Property(tries = 1000)
    void backReferencesRoundTripAndResolve(@ForAll("withReferences") PhpArray value) {
        byte[] bytes = PhpSerialize.serialize(value);
        assertThat(PhpSerialize.parse(bytes)).isEqualTo(value);
        assertThat(PhpSerialize.serialize(PhpSerialize.parse(bytes))).isEqualTo(bytes);
        java.util.List<?> plain = (java.util.List<?>) value.toJavaObject();
        assertThat(plain.get(1)).isEqualTo(plain.get(0));
    }
}
