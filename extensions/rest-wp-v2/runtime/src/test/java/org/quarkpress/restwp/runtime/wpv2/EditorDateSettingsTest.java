package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** The zone abbreviation at fixed instants, compared with what PHP's {@code 'T'} format prints for the same zone. */
class EditorDateSettingsTest {

    private static final Instant WINTER = Instant.parse("2026-01-15T12:00:00Z");

    private static final Instant SUMMER = Instant.parse("2026-07-01T12:00:00Z");

    @Test
    void namedZonesKeepTheirNames() {
        assertThat(abbr("Europe/Paris", WINTER)).isEqualTo("CET");
        assertThat(abbr("Europe/Paris", SUMMER)).isEqualTo("CEST");
        assertThat(abbr("America/New_York", SUMMER)).isEqualTo("EDT");
        assertThat(abbr("UTC", SUMMER)).isEqualTo("UTC");
    }

    @Test
    void aBareGmtIsKeptOnlyAtOffsetZero() {
        assertThat(abbr("Europe/London", WINTER)).isEqualTo("GMT");
        assertThat(abbr("Europe/London", SUMMER)).isEqualTo("BST");
        assertThat(abbr("Atlantic/Reykjavik", WINTER)).isEqualTo("GMT");
        assertThat(abbr("Atlantic/Reykjavik", SUMMER)).isEqualTo("GMT");
        // Java names Troll GMT at +02:00 in its summer; PHP prints +02
        assertThat(abbr("Antarctica/Troll", SUMMER)).isEqualTo("+02");
    }

    @Test
    void zonesWithoutANameGetPhpsNumericForm() {
        assertThat(abbr("America/Sao_Paulo", SUMMER)).isEqualTo("-03");
        assertThat(abbr("Asia/Kathmandu", SUMMER)).isEqualTo("+0545");
        assertThat(abbr("Pacific/Chatham", WINTER)).isEqualTo("+1345");
        assertThat(abbr("Etc/GMT+5", SUMMER)).isEqualTo("-05");
        assertThat(abbr("Asia/Dubai", SUMMER)).isEqualTo("+04");
    }

    private static String abbr(String zone, Instant at) {
        return EditorDateSettings.abbreviation(ZoneId.of(zone), at);
    }
}
