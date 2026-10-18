package org.quarkpress.multisite.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NonApplicationPathTest {

    @AfterEach
    void restore() {
        NonApplicationPath.install("/q/");
    }

    @Test
    void theRootIsNormalized() {
        assertThat(NonApplicationPath.normalize("/q/")).isEqualTo("/q/");
        assertThat(NonApplicationPath.normalize("/q")).isEqualTo("/q/");
        assertThat(NonApplicationPath.normalize("q")).isEqualTo("/q/");
        assertThat(NonApplicationPath.normalize("/app/q//")).isEqualTo("/app/q/");
        assertThat(NonApplicationPath.normalize("/")).isEmpty();
        assertThat(NonApplicationPath.normalize("")).isEmpty();
    }

    @Test
    void requestsUnderTheRootAreCovered() {
        NonApplicationPath.install("/q/");
        assertThat(NonApplicationPath.covers("/q")).isTrue();
        assertThat(NonApplicationPath.covers("/q/health")).isTrue();
        assertThat(NonApplicationPath.covers("/quux")).isFalse();
        assertThat(NonApplicationPath.covers("/")).isFalse();
        assertThat(NonApplicationPath.covers("/news/q/x")).isFalse();
    }

    @Test
    void aRootThatIsTheApplicationRootCoversNothing() {
        NonApplicationPath.install("/");
        assertThat(NonApplicationPath.covers("/q/health")).isFalse();
        assertThat(NonApplicationPath.covers("/")).isFalse();
    }
}
