package org.quarkpress.scheduling.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchTest {

    @Test
    void aConfiguredSizeOfAtLeastOneIsUsedAsIs() {
        assertThat(Batch.size(1)).isEqualTo(1);
        assertThat(Batch.size(100)).isEqualTo(100);
    }

    @Test
    void zeroAndNegativeSizesCountAsOne() {
        assertThat(Batch.size(0)).isEqualTo(1);
        assertThat(Batch.size(-5)).isEqualTo(1);
        assertThat(Batch.size(Integer.MIN_VALUE)).isEqualTo(1);
    }
}
