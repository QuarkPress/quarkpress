package org.quarkpress.lib.wpkses;

/**
 * Changes the allowlist of a context, like WordPress's {@code wp_kses_allowed_html} filter. Filters run in the
 * order they were added, once per {@link Kses} instance for the built-in and registered contexts.
 * Never called for an explicit {@link Allowlist} passed to {@code sanitize}.
 *
 * <p>For context names that were not registered or built in, filters run on every {@code sanitize} call, possibly
 * concurrently from several threads, so an implementation must be thread-safe.
 */
@FunctionalInterface
public interface AllowedHtmlFilter {

    /** Returns the allowlist to use for {@code context}; must not return null. */
    Allowlist filter(Allowlist tags, KsesContext context);
}
