package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CapabilityDeniedExceptionTest {

    @Test
    void aRefusalSaysWhetherTheCallerWasAnonymous() {
        CapabilityDeniedException anonymous = new CapabilityDeniedException("create_sites", true);
        assertThat(anonymous.anonymous()).isTrue();
        assertThat(anonymous.capability()).isEqualTo("create_sites");
        assertThat(anonymous).hasMessage("the caller lacks the network capability create_sites");
        assertThat(new CapabilityDeniedException("create_sites", false).anonymous())
                .isFalse();
    }

    @Test
    void theOneArgumentFormRefusesAnAuthenticatedCaller() {
        CapabilityDeniedException denied = new CapabilityDeniedException("manage_sites");
        assertThat(denied.anonymous()).isFalse();
        assertThat(denied).hasMessage("the caller lacks the network capability manage_sites");
    }
}
