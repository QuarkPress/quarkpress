package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class CapitalPModeTest {

    @Test
    void onlyTheTwoTitleFiltersAreSimple() { // A-G103..G105
        assertThat(CapitalPMode.forFilter("the_title")).isEqualTo(CapitalPMode.SIMPLE);
        assertThat(CapitalPMode.forFilter("wp_title")).isEqualTo(CapitalPMode.SIMPLE);
        assertThat(CapitalPMode.forFilter("document_title")).isEqualTo(CapitalPMode.JUDICIOUS);
        assertThat(CapitalPMode.forFilter("the_content")).isEqualTo(CapitalPMode.JUDICIOUS);
        assertThat(CapitalPMode.forFilter("")).isEqualTo(CapitalPMode.JUDICIOUS);
        assertThat(CapitalPMode.forFilter("THE_TITLE")).isEqualTo(CapitalPMode.JUDICIOUS);
        assertThatNullPointerException().isThrownBy(() -> CapitalPMode.forFilter(null));
    }
}
