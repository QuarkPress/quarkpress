package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class IdsTest {

    @Test
    void parsesCanonicalPositiveDecimals() {
        assertThat(SiteId.parse("1")).contains(SiteId.of(1));
        assertThat(SiteId.parse("42")).contains(SiteId.of(42));
        assertThat(PostId.parse("9223372036854775807")).contains(PostId.of(Long.MAX_VALUE));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "0",
                "-1",
                "+1",
                "01",
                " 1",
                "1 ",
                "1.0",
                "1e3",
                "0x1F",
                "9223372036854775808",
                "99999999999999999999",
                "\u0661",
                "\uff11"
            })
    void rejectsEverythingElse(String text) {
        assertThat(SiteId.parse(text)).isEmpty();
        assertThat(UserId.parse(text)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(TermId.parse(null)).isEmpty();
    }

    @Test
    void constructorsRejectZeroAndNegatives() {
        assertThatThrownBy(() -> SiteId.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RevisionId.of(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserId(Long.MIN_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsOfDifferentKindsAreNotEqual() {
        assertThat((Object) SiteId.of(7)).isNotEqualTo(PostId.of(7));
    }

    @Test
    void everyPositiveLongRoundTrips() {
        Properties.check(
                "IdsTest.roundTrip",
                2000,
                Gens.longs(1, Long.MAX_VALUE),
                value -> assertThat(SiteId.parse(Long.toString(value))).contains(SiteId.of(value)));
    }

    @Test
    void anyAcceptedTextIsCanonical() {
        Properties.check(
                "IdsTest.canonical",
                5000,
                Gens.oneOf(Gens.strings("0123456789+- ", 0, 21), Gens.strings(Gens.unicodeChars(), 0, 21)),
                text -> SiteId.parse(text)
                        .ifPresent(id -> assertThat(Long.toString(id.value())).isEqualTo(text)));
    }
}
