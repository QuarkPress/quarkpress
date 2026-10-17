package org.quarkpress.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class SiteZonesTest {

    @Test
    void aZoneNameWins() {
        assertThat(SiteZones.zoneOf("America/Toronto", "3")).isEqualTo(ZoneId.of("America/Toronto"));
    }

    @Test
    void otherwiseTheOffsetInHoursIsUsedTruncatedToMinutes() {
        assertThat(SiteZones.zoneOf("", "-5")).isEqualTo(ZoneOffset.ofHours(-5));
        assertThat(SiteZones.zoneOf("", "5.5")).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
        assertThat(SiteZones.zoneOf("", "5.75")).isEqualTo(ZoneOffset.ofHoursMinutes(5, 45));
        assertThat(SiteZones.zoneOf("", "-0.5")).isEqualTo(ZoneOffset.ofHoursMinutes(0, -30));
        assertThat(SiteZones.zoneOf("", "5.123")).isEqualTo(ZoneOffset.ofHoursMinutes(5, 7));
        assertThat(SiteZones.zoneOf("Not/AZone", "2")).isEqualTo(ZoneOffset.ofHours(2));
    }

    @Test
    void anythingElseIsUtc() {
        assertThat(SiteZones.zoneOf("", "")).isEqualTo(ZoneOffset.UTC);
        assertThat(SiteZones.zoneOf(null, null)).isEqualTo(ZoneOffset.UTC);
        assertThat(SiteZones.zoneOf("", "0")).isEqualTo(ZoneOffset.UTC);
        assertThat(SiteZones.zoneOf("", "1e3")).isEqualTo(ZoneOffset.UTC);
        assertThat(SiteZones.zoneOf("", "99")).isEqualTo(ZoneOffset.UTC);
        assertThat(SiteZones.zoneOf("", "--1")).isEqualTo(ZoneOffset.UTC);
        assertThat(SiteZones.zoneOf("", "1".repeat(40))).isEqualTo(ZoneOffset.UTC);
    }

    /*
     * Expected values below were checked with PHP 8.4 running wp_timezone_string() and wp_timezone() from the pinned
     * WordPress 7.1.3 sources with stubbed options.
     */

    @Test
    void minutesAreTruncatedFromTheFloatingPointFractionAsWordPressDoes() {
        // (5.1 - 5) * 60 is 5.99999... in floating point, so WordPress gives +05:05, not +05:06
        assertThat(SiteZones.zoneOf("", "5.1")).isEqualTo(ZoneOffset.ofHoursMinutes(5, 5));
        assertThat(SiteZones.zoneOf("", "-5.1")).isEqualTo(ZoneOffset.ofHoursMinutes(-5, -5));
        assertThat(SiteZones.zoneOf("", "4.3")).isEqualTo(ZoneOffset.ofHoursMinutes(4, 17));
        assertThat(SiteZones.zoneOf("", "0.016")).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void offsetsAreReadAsPhpReadsANumericString() {
        assertThat(SiteZones.zoneOf("", "+5")).isEqualTo(ZoneOffset.ofHours(5));
        assertThat(SiteZones.zoneOf("", ".5")).isEqualTo(ZoneOffset.ofHoursMinutes(0, 30));
        assertThat(SiteZones.zoneOf("", "5.")).isEqualTo(ZoneOffset.ofHours(5));
        assertThat(SiteZones.zoneOf("", " 5")).isEqualTo(ZoneOffset.ofHours(5));
        assertThat(SiteZones.zoneOf("", "5 ")).isEqualTo(ZoneOffset.ofHours(5));
        assertThat(SiteZones.zoneOf("", "5.5abc")).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
        assertThat(SiteZones.zoneOf("", "1e1")).isEqualTo(ZoneOffset.ofHours(10));
        assertThat(SiteZones.zoneOf("", "abc")).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void offsetTextIsScannedAsPhpScansIt() {
        assertThat(SiteZones.zoneOf("", "5" + " ".repeat(70))).isEqualTo(ZoneOffset.ofHours(5));
        assertThat(SiteZones.zoneOf("", "\u000B5")).isEqualTo(ZoneOffset.ofHours(5));
        assertThat(SiteZones.zoneOf("", "\f5")).isEqualTo(ZoneOffset.ofHours(5));
        assertThat(SiteZones.zoneOf("", "1.e1")).isEqualTo(ZoneOffset.ofHours(10));
        assertThat(SiteZones.zoneOf("", "-.5")).isEqualTo(ZoneOffset.ofHoursMinutes(0, -30));
        assertThat(SiteZones.zoneOf("", "1e")).isEqualTo(ZoneOffset.ofHours(1));
        assertThat(SiteZones.zoneOf("", ".e1")).isEqualTo(ZoneOffset.UTC);
        assertThat(SiteZones.zoneOf("", "0".repeat(70) + "5")).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void onlyIanaRegionIdsAreZoneNames() {
        assertThat(SiteZones.zoneOf(" Europe/Paris", "0")).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(SiteZones.zoneOf("\tEurope/Paris", "0")).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(SiteZones.zoneOf("Etc/GMT+5", "0")).isEqualTo(ZoneId.of("Etc/GMT+5"));
        assertThat(SiteZones.zoneOf("UTC+5", "2")).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(SiteZones.zoneOf("UTC-03:30", "2")).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(SiteZones.zoneOf("UT", "2")).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(SiteZones.zoneOf("Z", "2")).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(SiteZones.zoneOf("+05:00", "2")).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(SiteZones.zoneOf("SystemV/EST5", "2")).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(SiteZones.zoneOf("EST", "2")).isEqualTo(ZoneOffset.ofHours(2));
    }

    @Test
    void zoneNamesMatchIgnoringCaseAndAFalsyNameUsesTheOffset() {
        assertThat(SiteZones.zoneOf("america/toronto", "0")).isEqualTo(ZoneId.of("America/Toronto"));
        assertThat(SiteZones.zoneOf("europe/PARIS", "0")).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(SiteZones.zoneOf("Asia/Calcutta", "0")).isEqualTo(ZoneId.of("Asia/Calcutta"));
        assertThat(SiteZones.zoneOf("UTC", "3")).isEqualTo(ZoneId.of("UTC"));
        assertThat(SiteZones.zoneOf("0", "3")).isEqualTo(ZoneOffset.ofHours(3));
    }

    @Test
    void offsetsJavaCannotRepresentAreUtc() {
        // WordPress accepts these; java.time offsets stop at 18 hours
        assertThat(SiteZones.zoneOf("", "18")).isEqualTo(ZoneOffset.ofHours(18));
        assertThat(SiteZones.zoneOf("", "19")).isEqualTo(ZoneOffset.UTC);
        assertThat(SiteZones.zoneOf("", "-18.5")).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void offsetsStayInRangeAndOnWholeMinutes() {
        Properties.check("SiteZonesTest.offsets", 5000, Gens.strings("0123456789.+-eE", 0, 24), offset -> {
            ZoneId zone = SiteZones.zoneOf("", offset);
            assertThat(zone).isInstanceOf(ZoneOffset.class);
            int seconds = ((ZoneOffset) zone).getTotalSeconds();
            assertThat(Math.abs(seconds)).isLessThanOrEqualTo(18 * 3600);
            assertThat(seconds % 60).isZero();
        });
    }

    @Test
    void wholeHourOffsetsAreExact() {
        Properties.check(
                "SiteZonesTest.hours",
                500,
                Gens.ints(-18, 18),
                hours -> assertThat(SiteZones.zoneOf("", Integer.toString(hours)))
                        .isEqualTo(ZoneOffset.ofHours(hours)));
    }
}
