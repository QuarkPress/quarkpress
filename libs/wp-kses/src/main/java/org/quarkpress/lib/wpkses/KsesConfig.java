package org.quarkpress.lib.wpkses;

import java.util.List;
import java.util.Map;
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

    /** WordPress's tables and lists; {@code uploadBaseUrl} may be null. Contexts are resolved on every call. */
    static KsesConfig defaults(boolean defaultPreFilters, String uploadBaseUrl) {
        WordPressTables tables = WordPressTables.INSTANCE;
        SafeCss safeCss = new SafeCss(tables.safeStyleProperties(), null, tables.protocols());
        List<AllowedHtmlFilter> filters = defaultPreFilters ? List.of(Contexts.NOTE_MENTION) : List.of();
        return new KsesConfig(
                tables.protocols(),
                Set.copyOf(tables.uriAttributes()),
                safeCss,
                new AttributeChecker.Environment(safeCss::filter, PdfObjects.policy(uploadBaseUrl)),
                defaultPreFilters,
                List.of(),
                context -> Contexts.resolve(context, Map.of(), filters));
    }
}
