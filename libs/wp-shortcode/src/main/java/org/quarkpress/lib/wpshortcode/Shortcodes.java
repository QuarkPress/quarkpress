package org.quarkpress.lib.wpshortcode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Processes WordPress shortcodes as WordPress 7.1.3 does. Immutable and thread-safe: build one per registry and
 * share it.
 *
 * <pre>{@code
 * Shortcodes shortcodes = Shortcodes.builder(registry).build();
 * String html = shortcodes.apply(postContent);
 * }</pre>
 */
public final class Shortcodes {

    private final ShortcodeRegistry registry;
    private final ShortcodeHooks hooks;
    private final HtmlAttributeSanitizer sanitizer;
    private final ShortcodeOptions options;

    private Shortcodes(Builder b) {
        this.registry = b.registry;
        this.hooks = b.hooks;
        this.sanitizer = b.sanitizer;
        this.options = b.options;
    }

    public static Builder builder(ShortcodeRegistry registry) {
        return new Builder(registry);
    }

    /** WordPress's do_shortcode / apply_shortcodes. */
    public String apply(String content) {
        return apply(content, false);
    }

    /** do_shortcode with ignore_html: when true, shortcodes inside HTML tag markup are left alone. */
    public String apply(String content, boolean ignoreHtml) {
        Objects.requireNonNull(content, "content");
        Frame root = Frame.root(this);
        try {
            return expand(content, ignoreHtml, root);
        } finally {
            root.finish();
        }
    }

    /** One do_shortcode run; its handlers receive {@code frame}. */
    String expand(String content, boolean ignoreHtml, Frame frame) {
        if (content.indexOf('[') < 0 || registry.isEmpty()) {
            return content;
        }
        List<String> active = TagPrefilter.active(content, registry.names());
        if (active.isEmpty()) {
            return content;
        }
        ShortcodeScanner scanner = new ShortcodeScanner(active);
        Function<ShortcodeScanner.Hit, String> render = hit -> render(hit, frame);
        String guarded = HtmlProtector.protect(
                EntityCodec.normalize(content), ignoreHtml, text -> scanner.replace(text, render), sanitizer);
        return EntityCodec.unescape(scanner.replace(guarded, render).text());
    }

    int maxDepth() {
        return options.maxDepth();
    }

    private String render(ShortcodeScanner.Hit hit, Frame frame) {
        ShortcodeMatch m = hit.match();
        if (m.escaped()) {
            return unwrap(m);
        }
        Shortcode shortcode = shortcode(hit);
        Optional<String> replaced = hooks.preRender(shortcode);
        if (replaced.isPresent()) {
            return replaced.get();
        }
        String body = registry.handler(m.name()).orElseThrow().render(shortcode, frame);
        return hooks.postRender(m.openEscape() + (body == null ? "" : body) + m.closeEscape(), shortcode);
    }

    /** The record handlers and hooks see; its attributes know this processor's attribute-defaults hooks. */
    Shortcode shortcode(ShortcodeScanner.Hit hit) {
        ShortcodeMatch m = hit.match();
        ShortcodeAttributes attributes =
                ShortcodeAttributes.parse(m.attributeText()).bind(hooks::attributeDefaults);
        return new Shortcode(
                m.name(),
                attributes,
                m.content(),
                hit.enclosing(),
                !m.selfClosingSlash().isEmpty(),
                m);
    }

    /** An escaped match renders as itself minus one bracket on each side. */
    static String unwrap(ShortcodeMatch m) {
        return m.whole().substring(1, m.whole().length() - 1);
    }

    /** Configures a {@link Shortcodes}; defaults: no hooks, {@link BasicAttributeSanitizer}, default options. */
    public static final class Builder {
        private final ShortcodeRegistry registry;
        private ShortcodeHooks hooks = ShortcodeHooks.NONE;
        private HtmlAttributeSanitizer sanitizer = BasicAttributeSanitizer.INSTANCE;
        private ShortcodeOptions options = ShortcodeOptions.DEFAULTS;

        private Builder(ShortcodeRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
        }

        public Builder hooks(ShortcodeHooks hooks) {
            this.hooks = Objects.requireNonNull(hooks, "hooks");
            return this;
        }

        public Builder attributeSanitizer(HtmlAttributeSanitizer sanitizer) {
            this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
            return this;
        }

        public Builder options(ShortcodeOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        public Shortcodes build() {
            return new Shortcodes(this);
        }
    }
}
