package org.quarkpress.lib.wpkses;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** The immutable configuration a {@link Kses} instance shares between calls. */
record KsesConfig(
        List<String> protocols,
        Set<String> uriAttributes,
        SafeCss safeCss,
        AttributeChecker.Environment checks,
        boolean defaultPreFilters,
        List<PreKsesFilter> preFilters,
        Function<KsesContext, Allowlist> allowlists) {

    KsesConfig {
        protocols = List.copyOf(protocols);
        uriAttributes = Set.copyOf(uriAttributes);
        Objects.requireNonNull(safeCss, "safeCss");
        Objects.requireNonNull(checks, "checks");
        preFilters = List.copyOf(preFilters);
        Objects.requireNonNull(allowlists, "allowlists");
    }
}
