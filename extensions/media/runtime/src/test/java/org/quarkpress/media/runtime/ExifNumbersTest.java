package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExifNumbersTest {

    @Test
    void exifNumbersAsPhpWritesThem() {
        assertThat(ExifNumbers.text(ExifNumbers.frac2dec("28/10"))).isEqualTo("2.8");
        assertThat(ExifNumbers.text(ExifNumbers.frac2dec("8/1"))).isEqualTo("8");
        assertThat(ExifNumbers.text(ExifNumbers.frac2dec("50/1"))).isEqualTo("50");
        assertThat(ExifNumbers.text(ExifNumbers.frac2dec("1/60"))).isEqualTo("0.016666666666667");
        assertThat(ExifNumbers.text(ExifNumbers.frac2dec("1/3"))).isEqualTo("0.33333333333333");
        assertThat(ExifNumbers.text(ExifNumbers.frac2dec("1/250"))).isEqualTo("0.004");
        assertThat(ExifNumbers.text(ExifNumbers.frac2dec("1/100000"))).isEqualTo("1.0E-5");
        assertThat(ExifNumbers.frac2dec("1/0")).isEqualTo(0L);
        assertThat(ExifNumbers.date2ts("2023:10:15 14:30:00")).hasValue(1697380200L);
        assertThat(ExifNumbers.date2ts("garbage")).isEmpty();
        assertThat(ExifNumbers.date2ts("2023:10:15")).hasValue(1697328000L);
        assertThat(ExifNumbers.date2ts("2023-10-15 14:30:00")).isEmpty();
        assertThat(ExifNumbers.text(ExifNumbers.frac2dec("-1/2"))).isEqualTo("-0.5");
        assertThat(ExifNumbers.frac2dec("2/1/3")).isEqualTo(0L);
        assertThat(ExifNumbers.frac2dec("0/0")).isEqualTo(0L);
        assertThat(ExifNumbers.frac2dec("")).isEqualTo(0L);
        assertThat(ExifNumbers.frac2dec("abc")).isEqualTo(0L);
    }

    @Test
    void fractionTypesAsPhpGivesThem() {
        assertThat(ExifNumbers.frac2dec("8/1")).isEqualTo(8L);
        assertThat(ExifNumbers.frac2dec("50/1")).isEqualTo(50L);
        assertThat(ExifNumbers.frac2dec("4/2")).isEqualTo(2L);
        assertThat(ExifNumbers.frac2dec("28/10")).isEqualTo(2.8);
        assertThat(ExifNumbers.frac2dec("5")).isEqualTo(5.0);
        assertThat(ExifNumbers.frac2dec("2.5")).isEqualTo(2.5);
        assertThat(ExifNumbers.frac2dec("1.0/1")).isEqualTo(1.0);
        assertThat(ExifNumbers.frac2dec("1e2/1")).isEqualTo(100.0);
        assertThat(ExifNumbers.frac2dec(" 1 / 2 ")).isEqualTo(0.5);
        assertThat(ExifNumbers.frac2dec("1/0.0")).isEqualTo(0L);
        assertThat(ExifNumbers.frac2dec("0/5")).isEqualTo(0L);
        assertThat(ExifNumbers.frac2dec("/")).isEqualTo(0L);
        assertThat(ExifNumbers.frac2dec("1/")).isEqualTo(0L);
        assertThat(ExifNumbers.frac2dec("0x1A")).isEqualTo(0L);
        assertThat(ExifNumbers.frac2dec("9223372036854775807/1")).isEqualTo(Long.MAX_VALUE);
        assertThat(ExifNumbers.frac2dec("-9223372036854775808/-1")).isEqualTo(9.223372036854775807E18);
        assertThat(ExifNumbers.frac2dec("1".repeat(200))).isEqualTo(0L);
        assertThat(ExifNumbers.text(1.0E15)).isEqualTo("1.0E+15");
        assertThat(ExifNumbers.text(1697380200L)).isEqualTo("1697380200");
    }

    @Test
    void phpNumericStrings() {
        assertThat(ExifNumbers.isNumeric(" 5")).isTrue();
        assertThat(ExifNumbers.isNumeric("5 ")).isTrue();
        assertThat(ExifNumbers.isNumeric("\n5\n")).isTrue();
        assertThat(ExifNumbers.isNumeric(".5")).isTrue();
        assertThat(ExifNumbers.isNumeric("5.")).isTrue();
        assertThat(ExifNumbers.isNumeric("-0")).isTrue();
        assertThat(ExifNumbers.isNumeric("1e")).isFalse();
        assertThat(ExifNumbers.isNumeric(".")).isFalse();
        assertThat(ExifNumbers.isNumeric("  ")).isFalse();
        assertThat(ExifNumbers.isNumeric("1_000")).isFalse();
        assertThat(ExifNumbers.isNumeric("3-5")).isFalse();
        assertThat(ExifNumbers.isNumeric("\u0663")).isFalse();
    }

    @Test
    void datesRollOverAsPhpDoes() {
        // values observed with PHP 8.4's strtotime on the strings WordPress builds
        assertThat(ExifNumbers.date2ts("2023:02:31")).hasValue(1677801600L);
        assertThat(ExifNumbers.date2ts("2023:00:15")).hasValue(1671062400L);
        assertThat(ExifNumbers.date2ts("2023:10:00")).hasValue(1696032000L);
        assertThat(ExifNumbers.date2ts("0000:00:00 00:00:00")).hasValue(-62169984000L);
        assertThat(ExifNumbers.date2ts("2023:10:15 24:30:00")).hasValue(1697416200L);
        assertThat(ExifNumbers.date2ts("2023:10:15 23:59:60")).hasValue(1697414400L);
        assertThat(ExifNumbers.date2ts("2023:10:15 14:30")).hasValue(1697380200L);
        assertThat(ExifNumbers.date2ts("2023:1:5 1:2:3")).hasValue(1672880523L);
        assertThat(ExifNumbers.date2ts("2023:10:32")).isEmpty();
        assertThat(ExifNumbers.date2ts("2023:13:01 00:00:00")).isEmpty();
        assertThat(ExifNumbers.date2ts("2023:10:15 25:00:00")).isEmpty();
        assertThat(ExifNumbers.date2ts("2023:10:15 12:60:00")).isEmpty();
        assertThat(ExifNumbers.date2ts("2023:10:15 14")).isEmpty();
        assertThat(ExifNumbers.date2ts("2023:10:15 14:30:")).isEmpty();
        assertThat(ExifNumbers.date2ts("")).isEmpty();
        assertThat(ExifNumbers.date2ts("    :  :     :  :  ")).isEmpty();
    }

    @Test
    void iptcDatesApplyTheirZone() {
        assertThat(ExifNumbers.iptcDateTime("20231015", "143000+0200")).hasValue(1697373000L);
        assertThat(ExifNumbers.iptcDateTime("20231015", "143000-0130")).hasValue(1697380200L + 5400);
        assertThat(ExifNumbers.iptcDateTime("20231015", "143000")).hasValue(1697380200L);
        assertThat(ExifNumbers.iptcDateTime("20231015", "143000Z")).hasValue(1697380200L);
        assertThat(ExifNumbers.iptcDateTime("2023101", "143000")).isEmpty();
        assertThat(ExifNumbers.iptcDateTime("20231015", "14300")).isEmpty();
        assertThat(ExifNumbers.iptcDateTime("20231315", "143000")).isEmpty();
    }

    @Test
    void parserEdges() {
        assertThat(ExifNumbers.date2ts("2023:10:15 23:59:61")).isEmpty();
        assertThat(ExifNumbers.iptcDateTime("20231015", "143000+2400")).isEmpty();
        assertThat(ExifNumbers.iptcDateTime("20231015", "143000+0260")).isEmpty();
        assertThat(ExifNumbers.iptcDateTime("20231015", "143000+02a0")).isEmpty();
        assertThat(ExifNumbers.iptcDateTime("20231015", "143000*0200")).isEmpty();
        assertThat(ExifNumbers.frac2dec("9223372036854775808/1")).isEqualTo(9.223372036854775808E18);
        assertThat(ExifNumbers.frac2dec("9223372036854775808")).isEqualTo(9.223372036854775808E18);
        for (char space : new char[] {' ', '\t', '\n', '\r', '\u000b', '\f'}) {
            assertThat(ExifNumbers.frac2dec(space + "8" + space + "/" + space + "1" + space))
                    .as("%04x", (int) space)
                    .isEqualTo(8L);
        }
        assertThat(ExifNumbers.frac2dec(" 8/1")).isEqualTo(0L);
        assertThat(ExifNumbers.frac2dec("1e+2/1")).isEqualTo(100.0);
        assertThat(ExifNumbers.frac2dec("1e-2")).isEqualTo(0.01);
        assertThat(ExifNumbers.frac2dec("1E2")).isEqualTo(100.0);
        assertThat(ExifNumbers.frac2dec("+5/-2")).isEqualTo(-2.5);
        assertThat(ExifNumbers.frac2dec(" ".repeat(ExifNumbers.MAX_TEXT - 3) + "8/1"))
                .isEqualTo(8L);
        assertThat(ExifNumbers.frac2dec(" ".repeat(ExifNumbers.MAX_TEXT - 2) + "8/1"))
                .isEqualTo(0L);
    }
}
