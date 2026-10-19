package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.runtime.LaunchMode;
import org.junit.jupiter.api.Test;

class DevMembershipSeederTest {

    @Test
    void seedsInDevModeWhenEnabled() {
        assertThat(DevMembershipSeeder.shouldSeed(LaunchMode.DEVELOPMENT, true)).isTrue();
    }

    @Test
    void neverSeedsWhenDisabledOrOutsideDevMode() {
        assertThat(DevMembershipSeeder.shouldSeed(LaunchMode.DEVELOPMENT, false))
                .isFalse();
        assertThat(DevMembershipSeeder.shouldSeed(LaunchMode.TEST, true)).isFalse();
        assertThat(DevMembershipSeeder.shouldSeed(LaunchMode.NORMAL, true)).isFalse();
        assertThat(DevMembershipSeeder.shouldSeed(LaunchMode.TEST, false)).isFalse();
    }
}
