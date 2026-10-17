package org.quarkpress.lib.wpkses;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** wp_kses_allowed_html: the allowlist for a context name. */
final class Contexts {

    /** WordPress's {@code _wp_kses_allow_note_mention_span}, registered with the default filters. */
    static final AllowedHtmlFilter NOTE_MENTION = (tags, context) -> {
        if (!context.equals(KsesContext.PRE_COMMENT_CONTENT)) {
            return tags;
        }
        ElementRule span = tags.element("span").orElse(ElementRule.noAttributes());
        return tags.toBuilder()
                .element("span", span.withAttribute("class", AttributeRule.allowed()))
                .build();
    };

    private static final List<String> FORM_ATTRIBUTES =
            List.of("action", "accept", "accept-charset", "enctype", "method", "name", "target");

    private Contexts() {}

    /** The table a context starts from, before filters; {@code bases} replaces built-in tables by context. */
    static Allowlist base(KsesContext context, Map<KsesContext, Allowlist> bases) {
        Allowlist registered = bases.get(context);
        if (registered != null) {
            return registered;
        }
        WordPressTables tables = WordPressTables.INSTANCE;
        return switch (context.name()) {
            case "post" -> tables.post();
            case "user_description", "pre_term_description", "pre_user_description" -> userDescription(tables.data());
            case "strip" -> Allowlist.empty();
            case "entities" -> entities();
            default -> tables.data();
        };
    }

    /** The base table passed through {@code filters}, with WordPress's form special case for "post". */
    static Allowlist resolve(KsesContext context, Map<KsesContext, Allowlist> bases, List<AllowedHtmlFilter> filters) {
        Allowlist tags = applyFilters(base(context, bases), context, filters);
        if (context.equals(KsesContext.POST)
                && !bases.containsKey(KsesContext.POST)
                && !tags.allows("form")
                && (tags.allows("input") || tags.allows("select"))) {
            Allowlist.Builder withForm = base(context, bases).toBuilder().element("form");
            for (String attribute : FORM_ATTRIBUTES) {
                withForm.attribute("form", attribute);
            }
            tags = applyFilters(withForm.build(), context, filters);
        }
        return tags;
    }

    private static Allowlist applyFilters(Allowlist tags, KsesContext context, List<AllowedHtmlFilter> filters) {
        Allowlist current = tags;
        for (AllowedHtmlFilter filter : filters) {
            current = Objects.requireNonNull(
                    filter.filter(current, context), "an AllowedHtmlFilter returned null for " + context.name());
        }
        return current;
    }

    /** The data table with rel and target added to a. */
    static Allowlist userDescription(Allowlist data) {
        return data.toBuilder().attribute("a", "rel").attribute("a", "target").build();
    }

    /** The "entities" context as an allowlist: the entity-name list's numeric keys 0..252. */
    static Allowlist entities() {
        Allowlist.Builder builder = Allowlist.builder();
        for (int i = 0; i < EntityNames.ALLOWED.size(); i++) {
            builder.element(Integer.toString(i), ElementRule.closingTagsOnly());
        }
        return builder.build();
    }
}
