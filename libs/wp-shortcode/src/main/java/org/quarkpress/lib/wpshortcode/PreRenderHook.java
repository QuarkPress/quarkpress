package org.quarkpress.lib.wpshortcode;

import java.util.Optional;

/** WordPress's pre_do_shortcode_tag filter. */
@FunctionalInterface
public interface PreRenderHook {

    /**
     * Receives the result of the previous hook ({@code Optional.empty()} first, WordPress's {@code false}).
     * Returning a present value, even "", replaces the whole rendering: the handler is not called, the escape
     * brackets are not added back and no {@link PostRenderHook} runs. Return {@code current} to pass.
     */
    Optional<String> before(Shortcode shortcode, Optional<String> current);
}
