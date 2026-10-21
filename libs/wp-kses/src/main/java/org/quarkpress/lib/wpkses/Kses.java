package org.quarkpress.lib.wpkses;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * WordPress's kses HTML sanitizer: same allowlists, protocol handling, CSS filtering and output bytes as
 * WordPress 7.1.3 (see the README for the few deliberate differences). Instances are immutable and thread-safe;
 * configure one with {@link #builder()} or use {@link #wordPress()}.
 */
public final class Kses {

    private static final List<KsesContext> BUILT_IN = List.of(
            KsesContext.POST,
            KsesContext.DATA,
            KsesContext.STRIP,
            KsesContext.ENTITIES,
            KsesContext.USER_DESCRIPTION,
            KsesContext.PRE_USER_DESCRIPTION,
            KsesContext.PRE_TERM_DESCRIPTION,
            KsesContext.PRE_COMMENT_CONTENT);

    private static final Kses WORDPRESS = builder().build();

    private final KsesConfig config;
    private final List<String> uriAttributes;

    private Kses(Builder b) {
        List<AllowedHtmlFilter> filters = new ArrayList<>();
        if (b.preFilters) {
            filters.add(Contexts.NOTE_MENTION);
        }
        filters.addAll(b.filters);
        Map<KsesContext, Allowlist> bases = Map.copyOf(b.bases);
        Map<KsesContext, Allowlist> eager = new LinkedHashMap<>();
        Set<KsesContext> contexts = new LinkedHashSet<>(BUILT_IN);
        contexts.addAll(bases.keySet());
        for (KsesContext context : contexts) {
            eager.put(context, Contexts.resolve(context, bases, filters));
        }
        this.uriAttributes = List.copyOf(b.uriAttributes);
        Map<KsesContext, Allowlist> resolvedContexts = Map.copyOf(eager);
        List<AllowedHtmlFilter> filterList = List.copyOf(filters);
        SafeCss safeCss = new SafeCss(b.safeStyleProperties, b.cssAllowFilter, b.protocols);
        this.config = new KsesConfig(
                b.protocols,
                Set.copyOf(b.uriAttributes),
                safeCss,
                new AttributeChecker.Environment(safeCss::filter, PdfObjects.policy(b.uploadBaseUrl)),
                b.preFilters,
                b.preKsesFilters,
                context -> {
                    Objects.requireNonNull(context, "context");
                    Allowlist known = resolvedContexts.get(context);
                    return known != null ? known : Contexts.resolve(context, bases, filterList);
                });
    }

    /** WordPress's defaults: default pre-filters on, default protocols and CSS rules, no PDF objects. */
    public static Kses wordPress() {
        return WORDPRESS;
    }

    /** A builder with WordPress's defaults for every option. */
    public static Builder builder() {
        return new Builder();
    }

    /** wp_kses_post: the "post" context. */
    public String post(String html) {
        return sanitize(html, KsesContext.POST);
    }

    /** wp_kses_data outside any filter: the "data" context. */
    public String data(String html) {
        return sanitize(html, KsesContext.DATA);
    }

    /** wp_kses with the configured protocols. */
    public String sanitize(String html, AllowedHtml allowedHtml) {
        return sanitize(html, allowedHtml, List.of());
    }

    /**
     * wp_kses with an explicit protocol list for attributes (an empty list means the configured one). CSS url()
     * values always use the configured list, as in WordPress.
     */
    public String sanitize(String html, AllowedHtml allowedHtml, List<String> protocols) {
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(allowedHtml, "allowedHtml");
        Objects.requireNonNull(protocols, "protocols");
        return KsesRun.sanitize(config, html, allowedHtml, protocols);
    }

    /** wp_kses_bad_protocol with the configured protocols. */
    public String badProtocol(String value) {
        return badProtocol(value, config.protocols());
    }

    /** wp_kses_bad_protocol with exactly these protocols (an empty list allows none). */
    public String badProtocol(String value, List<String> protocols) {
        Objects.requireNonNull(value, "value");
        return ProtocolFilter.filter(TextUtils.wellFormed(value), List.copyOf(protocols));
    }

    /** safecss_filter_attr. */
    public String safeCss(String css) {
        Objects.requireNonNull(css, "css");
        return config.safeCss().filter(TextUtils.wellFormed(css));
    }

    /**
     * wp_kses_one_attr, shaped for lib-wp-shortcode's {@code HtmlAttributeSanitizer} (pass {@code kses::oneAttr}):
     * the sanitized attribute text, or empty when WordPress's result is empty or only whitespace.
     */
    public Optional<String> oneAttr(String element, String attributeToken) {
        String result = oneAttrText(element, attributeToken);
        return TextUtils.phpTrim(result).isEmpty() ? Optional.empty() : Optional.of(result);
    }

    /** wp_kses_one_attr's exact result. */
    String oneAttrText(String element, String attributeToken) {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(attributeToken, "attributeToken");
        return OneAttr.apply(
                TextUtils.wellFormed(attributeToken),
                TextUtils.wellFormed(element),
                allowedHtml(KsesContext.POST),
                config);
    }

    /** wp_kses_allowed_html for a context, after the configured filters. */
    public Allowlist allowedHtml(KsesContext context) {
        Objects.requireNonNull(context, "context");
        return config.allowlists().apply(context);
    }

    /** The allowed URL protocols this instance filters with (kses_allowed_protocols). */
    public List<String> protocols() {
        return config.protocols();
    }

    /** The attributes this instance treats as URLs (wp_kses_uri_attributes). */
    public List<String> uriAttributes() {
        return uriAttributes;
    }

    /**
     * The allowed CSS properties (safe_style_css). An empty list means every declaration is kept unfiltered, as in
     * WordPress.
     */
    public List<String> safeStyleProperties() {
        return config.safeCss().properties();
    }

    /** wp_strip_all_tags: all tags removed, script and style elements with their content; then PHP-trimmed. */
    public static String stripAllTags(String text, boolean removeBreaks) {
        Objects.requireNonNull(text, "text");
        return StripTags.stripAllTags(TextUtils.wellFormed(text), removeBreaks);
    }

    /**
     * PHP's {@code strip_tags} without allowed tags: tags, comments and PHP blocks removed, the text between them
     * kept (script content included, unlike {@link #stripAllTags}).
     */
    public static String stripTags(String text) {
        Objects.requireNonNull(text, "text");
        return StripTags.phpStripTags(TextUtils.wellFormed(text));
    }

    /** wp_kses_normalize_entities in the "html" context. */
    public static String normalizeEntities(String text) {
        Objects.requireNonNull(text, "text");
        return EntityNormalizer.normalize(TextUtils.wellFormed(text), false);
    }

    /**
     * wp_pre_kses_less_than: each '<' with the text after it, up to the next '<' (not included), the next '>'
     * (included) or the end, is HTML-escaped when it holds no '>'.
     */
    public static String lessThan(String text) {
        Objects.requireNonNull(text, "text");
        return PreFilters.lessThan(TextUtils.wellFormed(text));
    }

    /**
     * _wp_specialchars with its defaults: '&', '<' and '>' become entities, an '&' that starts a character reference is
     * not encoded again (after normalizing references as kses does), and quotes are kept.
     */
    public static String specialChars(String text) {
        Objects.requireNonNull(text, "text");
        return HtmlEscaper.specialChars(TextUtils.wellFormed(text));
    }

    /** Configures a {@link Kses}; every option has WordPress's default. */
    public static final class Builder {

        private final Map<KsesContext, Allowlist> bases = new LinkedHashMap<>();
        private final List<AllowedHtmlFilter> filters = new ArrayList<>();
        private final List<PreKsesFilter> preKsesFilters = new ArrayList<>();
        private List<String> protocols = WordPressTables.INSTANCE.protocols();
        private List<String> uriAttributes = WordPressTables.INSTANCE.uriAttributes();
        private List<String> safeStyleProperties = WordPressTables.INSTANCE.safeStyleProperties();
        private CssAllowFilter cssAllowFilter;
        private String uploadBaseUrl;
        private boolean preFilters = true;

        private Builder() {}

        /** Replaces the table a context starts from (registering "post" also turns off the form special case). */
        public Builder allowlist(KsesContext context, Allowlist base) {
            bases.put(Objects.requireNonNull(context, "context"), Objects.requireNonNull(base, "base"));
            return this;
        }

        /** Adds a filter like wp_kses_allowed_html; filters run in the order added. */
        public Builder allowedHtmlFilter(AllowedHtmlFilter filter) {
            filters.add(Objects.requireNonNull(filter, "filter"));
            return this;
        }

        /** The allowed URL protocols (kses_allowed_protocols); duplicates are dropped, first occurrence kept. */
        public Builder protocols(List<String> protocols) {
            this.protocols = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(protocols, "protocols")));
            return this;
        }

        /** The attributes whose values are URLs (wp_kses_uri_attributes). */
        public Builder uriAttributes(List<String> uriAttributes) {
            this.uriAttributes = List.copyOf(Objects.requireNonNull(uriAttributes, "uriAttributes"));
            return this;
        }

        /**
         * The allowed CSS properties (safe_style_css). An empty list keeps EVERY declaration unfiltered (WordPress
         * parity); it does not mean "allow no CSS". To drop all CSS, remove the style attribute from the allowlist.
         */
        public Builder safeStyleProperties(List<String> properties) {
            this.safeStyleProperties = List.copyOf(Objects.requireNonNull(properties, "properties"));
            return this;
        }

        /** Overrides the verdict on CSS declarations (safecss_filter_attr_allow_css). */
        public Builder cssAllowFilter(CssAllowFilter filter) {
            this.cssAllowFilter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * The URL of the uploads directory, for WordPress's PDF-object rule. Without it, object
         * elements never keep their data attribute.
         *
         * <p>Only the host and port are compared. A host that {@link java.net.URI} cannot parse as a host (for
         * example one with underscores) makes every PDF object fail closed: no data URL is accepted.
         *
         * @throws IllegalArgumentException if {@code url} is not a valid URI
         */
        public Builder uploadBaseUrl(String url) {
            Objects.requireNonNull(url, "url");
            try {
                java.net.URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("uploadBaseUrl is not a valid URI: " + url, e);
            }
            this.uploadBaseUrl = url;
            return this;
        }

        /**
         * Whether WordPress's default kses filters run: the less-than and block-attribute pre-filters and
         * the note-mention span. Default true; false gives bare kses.
         */
        public Builder preFilters(boolean enabled) {
            this.preFilters = enabled;
            return this;
        }

        /** Adds a pre_kses filter; it runs after the default ones, in the order added. */
        public Builder preFilter(PreKsesFilter filter) {
            preKsesFilters.add(Objects.requireNonNull(filter, "filter"));
            return this;
        }

        /** Builds an immutable, thread-safe {@link Kses}; contexts and filters are resolved here. */
        public Kses build() {
            return new Kses(this);
        }
    }
}
