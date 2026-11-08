package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** How many images are decoded at once: the configured number, else bounded by the processors and the heap. */
class DecodeLimiterTest {

    static final long MIB = 1024L * 1024;

    @Test
    void theDefaultFollowsTheProcessorsAndTheHeap() {
        // 32 threads, 4 GiB: eight by the heap, not sixteen by the processors
        assertThat(DecodeLimiter.limit(Optional.empty(), 32, 4096 * MIB)).isEqualTo(8);
        // 8 threads, 64 GiB: four by the processors
        assertThat(DecodeLimiter.limit(Optional.empty(), 8, 65536 * MIB)).isEqualTo(4);
        // just under one decode's heap, or one processor: still one
        assertThat(DecodeLimiter.limit(Optional.empty(), 32, 511 * MIB)).isEqualTo(1);
        assertThat(DecodeLimiter.limit(Optional.empty(), 1, 65536 * MIB)).isEqualTo(1);
        assertThat(DecodeLimiter.limit(Optional.empty(), 16, 1535 * MIB)).isEqualTo(2);
        // a JVM without a heap limit
        assertThat(DecodeLimiter.limit(Optional.empty(), 12, Long.MAX_VALUE)).isEqualTo(6);
    }

    @Test
    void aConfiguredNumberWinsAndIsAtLeastOne() {
        assertThat(DecodeLimiter.limit(Optional.of(12), 2, 256 * MIB)).isEqualTo(12);
        assertThat(DecodeLimiter.limit(Optional.of(0), 32, 65536 * MIB)).isEqualTo(1);
        assertThat(DecodeLimiter.limit(Optional.of(-3), 32, 65536 * MIB)).isEqualTo(1);
    }
}
