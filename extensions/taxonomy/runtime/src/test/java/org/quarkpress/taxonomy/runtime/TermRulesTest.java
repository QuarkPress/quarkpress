package org.quarkpress.taxonomy.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.TermId;
import org.quarkpress.taxonomy.TermException;

class TermRulesTest {

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void textIsCastToAnIntegerAsPhpCastsIt() {
        assertThat(TermRules.phpInt("42")).isEqualTo(42);
        assertThat(TermRules.phpInt(" \t\n42abc")).isEqualTo(42);
        assertThat(TermRules.phpInt("+7")).isEqualTo(7);
        assertThat(TermRules.phpInt("-7")).isEqualTo(-7);
        assertThat(TermRules.phpInt("1e3")).isEqualTo(1000);
        assertThat(TermRules.phpInt("1.9")).isEqualTo(1);
        assertThat(TermRules.phpInt(".5e1")).isEqualTo(5);
        assertThat(TermRules.phpInt("5e")).isEqualTo(5);
        assertThat(TermRules.phpInt("0x1A")).isZero();
        assertThat(TermRules.phpInt("abc")).isZero();
        assertThat(TermRules.phpInt("")).isZero();
        assertThat(TermRules.phpInt("-")).isZero();
        assertThat(TermRules.phpInt(".")).isZero();
        assertThat(TermRules.phpInt("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
        assertThat(TermRules.phpInt("-99999999999999999999")).isEqualTo(Long.MIN_VALUE);
        assertThat(TermRules.phpInt("1e400")).isZero();
    }

    private static void refused(String name, String slug, String description, String detail) {
        for (boolean update : new boolean[] {false, true}) {
            assertThatThrownBy(() -> TermRules.requireStorable(name, slug, description, update))
                    .isInstanceOfSatisfying(TermException.class, refusal -> {
                        assertThat(refusal.code()).isEqualTo(update ? "db_update_error" : "db_insert_error");
                        assertThat(refusal.detail()).isEqualTo(detail);
                    })
                    .hasMessage(
                            update
                                    ? "Could not update term in the database."
                                    : "Could not insert term into the database.");
        }
    }

    @Test
    void namesAndSlugsHoldTwoHundredCharacters() {
        String emoji = cp(0x1F600);
        TermRules.requireStorable("x".repeat(200), "s".repeat(200), "", false);
        TermRules.requireStorable(emoji.repeat(200), emoji.repeat(200), "", true);
        refused("x".repeat(201), "s", "", "the name is longer than 200 characters");
        refused(emoji.repeat(201), "s", "", "the name is longer than 200 characters");
        refused("x", "s".repeat(201), "", "the slug is longer than 200 characters");
        refused("x", emoji.repeat(201), "", "the slug is longer than 200 characters");
    }

    @Test
    void aNulIsRefusedInNamesSlugsAndDescriptions() {
        refused("a" + (char) 0, "a", "", "the name holds a NUL character");
        refused("a", "s" + (char) 0, "", "the slug holds a NUL character");
        refused("a", "a", "d" + (char) 0, "the description holds a NUL character");
    }

    @Test
    void aParentOfZeroOrLessIsNone() {
        assertThat(TermRules.parent(OptionalLong.empty())).isEmpty();
        assertThat(TermRules.parent(OptionalLong.of(0))).isEmpty();
        assertThat(TermRules.parent(OptionalLong.of(-5))).isEmpty();
        assertThat(TermRules.parent(OptionalLong.of(7))).contains(TermId.of(7));
    }

    @Test
    void emptyIsPhpsEmpty() {
        assertThat(TermRules.phpEmpty("")).isTrue();
        assertThat(TermRules.phpEmpty("0")).isTrue();
        assertThat(TermRules.phpEmpty("00")).isFalse();
        assertThat(TermRules.phpEmpty(" ")).isFalse();
    }
}
