package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class RoundTripPropertyTest {

    @Test
    void serializeThenParseIsIdentity() {
        Properties.check(
                "RoundTripPropertyTest.serializeThenParseIsIdentity", 2000, PhpValueArbitraries.values(), v -> {
                    byte[] bytes = PhpSerialize.serialize(v);
                    PhpValue back = PhpSerialize.parse(bytes);
                    assertThat(back).isEqualTo(v);
                    assertThat(PhpSerialize.serialize(back)).isEqualTo(bytes);
                });
    }

    @Test
    void repairModeAcceptsEveryWellFormedDocument() {
        Properties.check(
                "RoundTripPropertyTest.repairModeAcceptsEveryWellFormedDocument",
                2000,
                PhpValueArbitraries.values(),
                v -> {
                    byte[] bytes = PhpSerialize.serialize(v);
                    assertThat(PhpSerialize.parse(bytes, ParseOptions.DEFAULTS.withRepairLengths(true)))
                            .isEqualTo(v);
                });
    }

    @Test
    void parserNeverThrowsAnythingButPhpSerializeException() {
        Properties.check(
                "RoundTripPropertyTest.parserNeverThrowsAnythingButPhpSerializeException",
                5000,
                Gens.bytes(0, 255),
                junk -> {
                    try {
                        PhpSerialize.parse(junk);
                    } catch (PhpSerializeException expected) {
                        // acceptable outcome for junk input
                    }
                });
    }

    /** A value to serialize, a position (modulo the length) and the byte to write there. */
    private record Mutation(PhpValue value, int at, byte b) {}

    @Test
    void mutatedValidInputNeverCrashes() {
        Gen<PhpValue> values = PhpValueArbitraries.values();
        Gen<Integer> positions = Gens.ints(0, 10_000);
        Gen<Integer> bytes = Gens.ints(Byte.MIN_VALUE, Byte.MAX_VALUE);
        Gen<Mutation> mutations = random -> new Mutation(
                values.next(random), positions.next(random), bytes.next(random).byteValue());
        Properties.check("RoundTripPropertyTest.mutatedValidInputNeverCrashes", 3000, mutations, m -> {
            byte[] serialized = PhpSerialize.serialize(m.value());
            serialized[m.at() % serialized.length] = m.b();
            try {
                PhpSerialize.parse(serialized, ParseOptions.DEFAULTS.withRepairLengths(true));
            } catch (PhpSerializeException expected) {
                // acceptable outcome for corrupted input
            }
        });
    }

    @Test
    void conversionsNeverThrow() {
        Properties.check("RoundTripPropertyTest.conversionsNeverThrow", 1000, PhpValueArbitraries.values(), v -> {
            v.toJavaObject();
            assertThat(v.toJson()).isNotEmpty();
        });
    }

    @Test
    void backReferencesRoundTripAndResolve() {
        Properties.check(
                "RoundTripPropertyTest.backReferencesRoundTripAndResolve",
                1000,
                PhpValueArbitraries.arraysWithBackReferences(),
                value -> {
                    byte[] bytes = PhpSerialize.serialize(value);
                    assertThat(PhpSerialize.parse(bytes)).isEqualTo(value);
                    assertThat(PhpSerialize.serialize(PhpSerialize.parse(bytes)))
                            .isEqualTo(bytes);
                    List<?> plain = (List<?>) value.toJavaObject();
                    assertThat(plain.get(1)).isEqualTo(plain.get(0));
                });
    }
}
