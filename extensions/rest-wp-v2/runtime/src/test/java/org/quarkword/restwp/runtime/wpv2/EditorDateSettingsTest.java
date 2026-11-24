package org.quarkword.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The zone abbreviation at fixed instants, compared with what PHP's {@code 'T'} format prints for the same zone. The
 * answers must not depend on the JDK's locale data, which names zones differently from one release to the next.
 */
class EditorDateSettingsTest {

    private static final Instant WINTER = Instant.parse("2026-01-15T12:00:00Z");

    private static final Instant SUMMER = Instant.parse("2026-07-01T12:00:00Z");

    @Test
    void namedZonesKeepTheirNames() {
        assertThat(abbr("Europe/Paris", WINTER)).isEqualTo("CET");
        assertThat(abbr("Europe/Paris", SUMMER)).isEqualTo("CEST");
        assertThat(abbr("America/New_York", SUMMER)).isEqualTo("EDT");
        assertThat(abbr("UTC", SUMMER)).isEqualTo("UTC");
        assertThat(abbr("Asia/Kolkata", SUMMER)).isEqualTo("IST");
        assertThat(abbr("Asia/Shanghai", SUMMER)).isEqualTo("CST");
        assertThat(abbr("Pacific/Guam", SUMMER)).isEqualTo("ChST");
    }

    @Test
    void namesJavaSpellsOtherwiseOrLacks() {
        // Java's older names of the Aleutian zone's letters
        assertThat(abbr("America/Adak", WINTER)).isEqualTo("HST");
        assertThat(abbr("America/Adak", SUMMER)).isEqualTo("HDT");
        assertThat(abbr("Africa/Windhoek", SUMMER)).isEqualTo("CAT");
    }

    @Test
    void aBareGmtIsKeptOnlyAtOffsetZero() {
        assertThat(abbr("Europe/London", WINTER)).isEqualTo("GMT");
        assertThat(abbr("Europe/London", SUMMER)).isEqualTo("BST");
        assertThat(abbr("Atlantic/Reykjavik", WINTER)).isEqualTo("GMT");
        assertThat(abbr("Atlantic/Reykjavik", SUMMER)).isEqualTo("GMT");
    }

    @Test
    void zonesWithoutANameGetPhpsNumericForm() {
        assertThat(abbr("America/Sao_Paulo", SUMMER)).isEqualTo("-03");
        assertThat(abbr("Asia/Kathmandu", SUMMER)).isEqualTo("+0545");
        assertThat(abbr("Pacific/Chatham", WINTER)).isEqualTo("+1345");
        assertThat(abbr("Etc/GMT+5", SUMMER)).isEqualTo("-05");
        assertThat(abbr("Asia/Dubai", SUMMER)).isEqualTo("+04");
    }

    @Test
    void zonesJavaNamesThoughTheirAbbreviationIsNumeric() {
        assertThat(abbr("Antarctica/Troll", WINTER)).isEqualTo("+00");
        assertThat(abbr("Antarctica/Troll", SUMMER)).isEqualTo("+02");
        assertThat(abbr("Africa/Casablanca", WINTER)).isEqualTo("+01");
        assertThat(abbr("Asia/Colombo", SUMMER)).isEqualTo("+0530");
        assertThat(abbr("Europe/Minsk", SUMMER)).isEqualTo("+03");
        assertThat(abbr("Antarctica/Casey", WINTER)).isEqualTo("+08");
        assertThat(abbr("Atlantic/Azores", SUMMER)).isEqualTo("+00");
        assertThat(abbr("Pacific/Pitcairn", SUMMER)).isEqualTo("-08");
    }

    private static String abbr(String zone, Instant at) {
        return EditorDateSettings.abbreviation(ZoneId.of(zone), at);
    }
}
