package org.quarkpress.lib.wpshortcode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Processes WordPress shortcodes as WordPress 7.1.3 does. Immutable and thread-safe: build one per registry and
 * share it.
 *
 * <pre>{@code
 * Shortcodes shortcodes = Shortcodes.builder(registry).build();
 * String html = shortcodes.apply(postContent);
 * }</pre>
 *
 * <table>
 *   <caption>WordPress mapping</caption>
 *   <tr><th>Method</th><th>WordPress</th></tr>
 *   <tr><td>{@link #apply(String)}</td><td>do_shortcode, apply_shortcodes</td></tr>
 *   <tr><td>{@link #apply(String, boolean)}</td><td>do_shortcode($content, $ignore_html)</td></tr>
 *   <tr><td>{@link #strip(String)}</td><td>strip_shortcodes</td></tr>
 *   <tr><td>{@link #has(String, String)}</td><td>has_shortcode</td></tr>
 *   <tr><td>{@link #tagsIn(String)}</td><td>get_shortcode_tags_in_content</td></tr>
 *   <tr><td>{@link #parse(String)}</td><td>(none)</td></tr>
 *   <tr><td>{@link #unautop(String)}</td><td>shortcode_unautop</td></tr>
 * </table>
 */
public final class Shortcodes {

    private final ShortcodeRegistry registry;
    private final ShortcodeHooks hooks;
    private final HtmlAttributeSanitizer sanitizer;
    private final ShortcodeOptions options;
    private final ShortcodeScanner allNames;
    private final Set<String> nameSet;
    private final int longestName;
    private final Unautop.Names unautopNames;

    private Shortcodes(Builder b) {
        this.registry = b.registry;
        this.hooks = b.hooks;
        this.sanitizer = b.sanitizer;
        this.options = b.options;
        this.allNames = new ShortcodeScanner(registry.names());
        this.nameSet = Set.copyOf(registry.names());
        this.longestName = TagPrefilter.longestOf(registry.names());
        this.unautopNames = Unautop.Names.of(registry.names());
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

    /** WordPress's strip_shortcodes: removes shortcodes (and enclosed content) outside HTML markup. */
    public String strip(String content) {
        Objects.requireNonNull(content, "content");
        if (content.indexOf('[') < 0 || registry.isEmpty()) {
            return content;
        }
        List<String> tags = hooks.stripTags(registry.names(), content);
        List<String> active = tags == registry.names()
                ? TagPrefilter.active(content, tags, nameSet, longestName)
                : TagPrefilter.active(content, tags);
        if (active.isEmpty()) {
            return content;
        }
        String stripped = new ShortcodeScanner(active)
                .replace(protectIgnoringHtml(content), hit -> {
                    ShortcodeMatch m = hit.match();
                    return m.escaped() ? unwrap(m) : m.openEscape() + m.closeEscape();
                })
                .text();
        return EntityCodec.unescape(stripped);
    }

    /**
     * WordPress's has_shortcode: true when {@code tag} is registered and matches anywhere, escaped shortcodes,
     * HTML attributes and nested content included.
     */
    public boolean has(String content, String tag) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(tag, "tag");
        if (content.indexOf('[') < 0 || !registry.contains(tag)) {
            return false;
        }
        Deque<String> pending = new ArrayDeque<>();
        pending.push(content);
        while (!pending.isEmpty()) {
            for (ShortcodeScanner.Hit hit : allNames.scan(pending.pop())) {
                if (hit.match().name().equals(tag)) {
                    return true;
                }
                if (hit.match().content().indexOf('[') >= 0) {
                    pending.push(hit.match().content());
                }
            }
        }
        return false;
    }

    /**
     * WordPress's get_shortcode_tags_in_content: the registered names that match, in document order, each
     * followed by the names nested in its content; duplicates and escaped shortcodes included.
     */
    public List<String> tagsIn(String content) {
        Objects.requireNonNull(content, "content");
        if (content.indexOf('[') < 0 || registry.isEmpty()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        // one queue per level, polled, so a consumed hit (and its content copy) is unreachable once its child scan
        // is pushed; depth-first document order is kept
        Deque<Deque<ShortcodeScanner.Hit>> stack = new ArrayDeque<>();
        stack.push(new ArrayDeque<>(allNames.scan(content)));
        while (!stack.isEmpty()) {
            ShortcodeScanner.Hit hit = stack.peek().poll();
            if (hit == null) {
                stack.pop();
                continue;
            }
            ShortcodeMatch m = hit.match();
            tags.add(m.name());
            if (m.content().indexOf('[') >= 0) {
                stack.push(new ArrayDeque<>(allNames.scan(m.content())));
            }
        }
        return List.copyOf(tags);
    }

    /**
     * The matches {@link #apply(String)} would render, without rendering: HTML is protected as with ignore_html,
     * so no handler runs and shortcodes inside HTML attributes are not reported. Content is not parsed; call
     * {@code parse} on {@link Shortcode#content()} for nested shortcodes.
     */
    public List<ShortcodeNode> parse(String content) {
        Objects.requireNonNull(content, "content");
        List<String> active = activeNames(content);
        String guarded = active.isEmpty() ? content : protectIgnoringHtml(content);
        List<ShortcodeNode> nodes = new ArrayList<>();
        int last = 0;
        if (!active.isEmpty()) {
            for (ShortcodeScanner.Hit hit : new ShortcodeScanner(active).scan(guarded)) {
                if (hit.start() > last) {
                    nodes.add(new ShortcodeNode.Text(guarded.substring(last, hit.start())));
                }
                nodes.add(
                        hit.match().escaped()
                                ? new ShortcodeNode.Escaped(hit.match())
                                : new ShortcodeNode.Match(shortcode(hit)));
                last = hit.end();
            }
        }
        if (last < guarded.length()) {
            nodes.add(new ShortcodeNode.Text(guarded.substring(last)));
        }
        return List.copyOf(nodes);
    }

    /** WordPress's shortcode_unautop: unwraps paragraphs that hold only a registered shortcode. */
    public String unautop(String content) {
        Objects.requireNonNull(content, "content");
        return Unautop.apply(content, unautopNames);
    }

    /** The text {@link #parse(String)} splits. */
    String protectForParse(String content) {
        return activeNames(content).isEmpty() ? content : protectIgnoringHtml(content);
    }

    /** One do_shortcode run; its handlers receive {@code frame}. */
    String expand(String content, boolean ignoreHtml, Frame frame) {
        List<String> active = activeNames(content);
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

    /** The early exits and prefilter shared by apply and parse: nothing to do without a bracket or a registered name, then only the names present. */
    private List<String> activeNames(String content) {
        if (content.indexOf('[') < 0 || registry.isEmpty()) {
            return List.of();
        }
        return TagPrefilter.active(content, registry.names(), nameSet, longestName);
    }

    private String protectIgnoringHtml(String content) {
        return HtmlProtector.protect(
                EntityCodec.normalize(content), true, text -> new ShortcodeScanner.Expansion(text, 0), sanitizer);
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
