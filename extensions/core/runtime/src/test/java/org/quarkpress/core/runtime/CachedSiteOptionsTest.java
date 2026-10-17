package org.quarkpress.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class CachedSiteOptionsTest {

    @Test
    void theWholeLongRangeParses() {
        assertThat(CachedSiteOptions.parseLong("-9223372036854775808")).contains(Long.MIN_VALUE);
        assertThat(CachedSiteOptions.parseLong("9223372036854775807")).contains(Long.MAX_VALUE);
        assertThat(CachedSiteOptions.parseLong("-9223372036854775807")).contains(-Long.MAX_VALUE);
        assertThat(CachedSiteOptions.parseLong("0")).contains(0L);
        assertThat(CachedSiteOptions.parseLong("-0")).contains(0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "-9223372036854775809", "", "-", "+1", "1-", "12345678901234567890"})
    void textOutsideTheRangeOrFormIsRefused(String text) {
        assertThat(CachedSiteOptions.parseLong(text)).isEmpty();
    }

    @Test
    void everyLongRoundTrips() {
        Properties.check(
                "CachedSiteOptionsTest.roundTrip",
                3000,
                Gens.longs(Long.MIN_VALUE, Long.MAX_VALUE),
                value -> assertThat(CachedSiteOptions.parseLong(Long.toString(value)))
                        .contains(value));
    }
}
