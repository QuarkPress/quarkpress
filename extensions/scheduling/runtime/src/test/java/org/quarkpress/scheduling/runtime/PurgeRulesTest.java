package org.quarkpress.scheduling.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PurgeRulesTest {

    static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    static JsonNode meta(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    @Test
    void theTrashThresholdIsNowMinusTheDaysInSeconds() {
        assertThat(PurgeRules.trashThreshold(NOW, 30)).isEqualTo(1765886400L);
        assertThat(PurgeRules.trashThreshold(NOW, 1)).isEqualTo(1768392000L);
        assertThat(PurgeRules.trashThreshold(NOW, 0)).isEqualTo(1768478400L);
    }

    @Test
    void theTrashTimeIsTheFirstValueReadAsANumber() {
        assertThat(PurgeRules.trashTime(meta("{\"_wp_trash_meta_time\":[1768478400]}")))
                .contains(1768478400L);
        assertThat(PurgeRules.trashTime(meta("{\"_wp_trash_meta_time\":[\"1768478400\"]}")))
                .contains(1768478400L);
        assertThat(PurgeRules.trashTime(meta("{\"_wp_trash_meta_time\":[5, 9]}")))
                .contains(5L);
        assertThat(PurgeRules.trashTime(meta("{\"_wp_trash_meta_time\":[\"abc\"]}")))
                .isEmpty();
        assertThat(PurgeRules.trashTime(meta("{\"_wp_trash_meta_time\":[\"1234567890123456789\"]}")))
                .isEmpty();
        assertThat(PurgeRules.trashTime(meta("{\"_wp_trash_meta_time\":[]}"))).isEmpty();
        assertThat(PurgeRules.trashTime(meta("{\"_wp_trash_meta_status\":[\"publish\"]}")))
                .isEmpty();
        assertThat(PurgeRules.trashTime(meta("{}"))).isEmpty();
        assertThat(PurgeRules.trashTime(meta("[]"))).isEmpty();
    }

    @Test
    void purgeableMeansStrictlyOlderThanTheThreshold() {
        assertThat(PurgeRules.trashPurgeable(meta("{\"_wp_trash_meta_time\":[1765886399]}"), 1765886400L))
                .isTrue();
        assertThat(PurgeRules.trashPurgeable(meta("{\"_wp_trash_meta_time\":[1765886400]}"), 1765886400L))
                .isFalse();
        assertThat(PurgeRules.trashPurgeable(meta("{}"), 1765886400L)).isFalse();
    }

    @Test
    void autoDraftsAgeOutAfterSevenDays() {
        assertThat(PurgeRules.autoDraftThreshold(NOW)).isEqualTo(Instant.parse("2026-01-08T12:00:00Z"));
    }
}
