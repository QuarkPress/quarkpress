package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PhpFloatFormatTest {

    record Case(double value, String serialized, String cast) {}

    static Case[] cases() {
        return new Case[] {
            new Case(1e7, "10000000", "10000000"),
            new Case(1e14, "100000000000000", "1.0E+14"),
            new Case(1e15, "1000000000000000", "1.0E+15"),
            new Case(1e16, "10000000000000000", "1.0E+16"),
            new Case(1e17, "1.0E+17", "1.0E+17"),
            new Case(1e22, "1.0E+22", "1.0E+22"),
            new Case(1e23, "1.0E+23", "1.0E+23"),
            new Case(1e25, "1.0E+25", "1.0E+25"),
            new Case(1e100, "1.0E+100", "1.0E+100"),
            new Case(1e-4, "0.0001", "0.0001"),
            new Case(1e-5, "1.0E-5", "1.0E-5"),
            new Case(1e-6, "1.0E-6", "1.0E-6"),
            new Case(0.00012345, "0.00012345", "0.00012345"),
            new Case(0.000099, "9.9E-5", "9.9E-5"),
            new Case(1.5e-5, "1.5E-5", "1.5E-5"),
            new Case(-1.5e-7, "-1.5E-7", "-1.5E-7"),
            new Case(123456789012345.6, "123456789012345.6", "1.2345678901235E+14"),
            new Case(1234567890123456.7, "1234567890123456.8", "1.2345678901235E+15"),
            new Case(12345678901234567.0, "12345678901234568", "1.2345678901235E+16"),
            new Case(99999999999999990.0, "99999999999999980", "1.0E+17"),
            new Case(1.5e16, "15000000000000000", "1.5E+16"),
            new Case(9007199254740993.0, "9007199254740992", "9.007199254741E+15"),
            new Case(99999999999999.0, "99999999999999", "99999999999999"),
            new Case(5e-324, "5.0E-324", "4.9406564584125E-324"),
            new Case(4.9e-322, "4.9E-322", "4.8912498938283E-322"),
            new Case(2.2250738585072014e-308, "2.2250738585072014E-308", "2.2250738585072E-308"),
            new Case(Double.MAX_VALUE, "1.7976931348623157E+308", "1.7976931348623E+308"),
            new Case(0.30000000000000004, "0.30000000000000004", "0.3"),
            new Case(1.0 / 3, "0.3333333333333333", "0.33333333333333"),
            new Case(0.1, "0.1", "0.1"),
            new Case(2.5, "2.5", "2.5"),
            new Case(1.0, "1", "1"),
            new Case(100.0, "100", "100"),
            new Case(1.5e300, "1.5E+300", "1.5E+300"),
            new Case(0.0, "0", "0"),
            new Case(-0.0, "-0", "-0"),
            new Case(Double.NaN, "NAN", "NAN"),
            new Case(Double.POSITIVE_INFINITY, "INF", "INF"),
            new Case(Double.NEGATIVE_INFINITY, "-INF", "-INF"),
            // exact ties at the 15th digit: the cast keeps the zeros of the 14 rounded digits (controller ruling M6)
            new Case(100000000000005.0, "100000000000005", "1.0000000000000E+14"),
            new Case(597007423915705.0, "597007423915705", "5.9700742391570E+14"),
            new Case(597007423915715.0, "597007423915715", "5.9700742391572E+14"),
            new Case(10000000000000.5, "10000000000000.5", "10000000000000"),
            // ties that round up, or at 1e15 and above, are trimmed (reviewer differential)
            new Case(709047635994295.0, "709047635994295", "7.090476359943E+14"),
            new Case(4667456504470050.0, "4667456504470050", "4.66745650447E+15"),
            new Case(8.94071920702095E14, "894071920702095", "8.940719207021E+14"),
            new Case(999999999999995.0, "999999999999995", "1.0E+15"),
            new Case(199999999999995.0, "199999999999995", "2.0E+14"),
            new Case(1000000000000050.0, "1000000000000050", "1.0E+15"),
            new Case(10000000000001.5, "10000000000001.5", "10000000000002"),
            new Case(10000000000002.5, "10000000000002.5", "10000000000002"),
        };
    }

    @ParameterizedTest
    @MethodSource("cases")
    void serializeTextMatchesPhp(Case c) {
        assertThat(PhpFloatFormat.shortest(c.value())).isEqualTo(c.serialized());
    }

    @ParameterizedTest
    @MethodSource("cases")
    void castTextMatchesPhp(Case c) {
        assertThat(PhpFloatFormat.castToString(c.value())).isEqualTo(c.cast());
    }
}
