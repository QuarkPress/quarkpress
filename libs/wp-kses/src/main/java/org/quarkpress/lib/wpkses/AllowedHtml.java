package org.quarkpress.lib.wpkses;

/**
 * What {@link Kses#sanitize(String, AllowedHtml)} allows: a context name that the {@link Kses} instance resolves
 * (WordPress's string argument to wp_kses), or an explicit {@link Allowlist} that is used as given.
 */
public sealed interface AllowedHtml permits Allowlist, KsesContext {}
