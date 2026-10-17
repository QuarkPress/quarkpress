package org.quarkpress.lib.wpshortcode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed counterparts of the WordPress shortcode filters. Hooks run in ascending priority, then in registration
 * order, each receiving the previous hook's result, as WordPress's apply_filters does. Immutable.
 */
public final class ShortcodeHooks {

    public static final int DEFAULT_PRIORITY = 10;

    /** No hooks. */
    public static final ShortcodeHooks NONE = builder().build();

    private record Registered<H>(int priority, long order, H hook) {}

    private final List<PreRenderHook> pre;
    private final List<PostRenderHook> post;
    private final Map<String, List<AttributeDefaultsHook>> defaults;
    private final List<StripTagsHook> strip;

    private ShortcodeHooks(Builder b) {
        this.pre = sorted(b.pre);
        this.post = sorted(b.post);
        Map<String, List<AttributeDefaultsHook>> byTag = new HashMap<>();
        b.defaults.forEach((tag, hooks) -> byTag.put(tag, sorted(hooks)));
        this.defaults = Map.copyOf(byTag);
        this.strip = sorted(b.strip);
    }

    public static Builder builder() {
        return new Builder();
    }

    Optional<String> preRender(Shortcode shortcode) {
        Optional<String> current = Optional.empty();
        for (PreRenderHook hook : pre) {
            current = Objects.requireNonNull(
                    hook.before(shortcode, current),
                    "a PreRenderHook returned null; return Optional.empty() (or the current value) to pass");
        }
        return current;
    }

    String postRender(String output, Shortcode shortcode) {
        String current = output;
        for (PostRenderHook hook : post) {
            String next = hook.after(current, shortcode);
            current = next == null ? "" : next;
        }
        return current;
    }

    Map<String, String> attributeDefaults(
            String tag, Map<String, String> out, Map<String, String> defaults, ShortcodeAttributes attributes) {
        List<AttributeDefaultsHook> hooks = this.defaults.get(tag);
        if (hooks == null) {
            return out;
        }
        Map<String, String> current = out;
        for (AttributeDefaultsHook hook : hooks) {
            current = Objects.requireNonNull(
                    hook.filter(current, defaults, attributes, tag), "an AttributeDefaultsHook returned null");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(current));
    }

    List<String> stripTags(List<String> tags, String content) {
        List<String> current = tags;
        for (StripTagsHook hook : strip) {
            List<String> result =
                    Objects.requireNonNull(hook.filter(current, content), "a StripTagsHook returned null");
            for (String tag : result) {
                Objects.requireNonNull(tag, "a StripTagsHook returned a list containing null");
            }
            current = List.copyOf(result);
        }
        return current;
    }

    private static <H> List<H> sorted(List<Registered<H>> hooks) {
        List<Registered<H>> copy = new ArrayList<>(hooks);
        copy.sort(Comparator.comparingInt((Registered<H> r) -> r.priority()).thenComparingLong(r -> r.order()));
        List<H> out = new ArrayList<>(copy.size());
        for (Registered<H> r : copy) {
            out.add(r.hook());
        }
        return List.copyOf(out);
    }

    /** Collects hooks; priorities default to {@link #DEFAULT_PRIORITY}. */
    public static final class Builder {
        private final List<Registered<PreRenderHook>> pre = new ArrayList<>();
        private final List<Registered<PostRenderHook>> post = new ArrayList<>();
        private final Map<String, List<Registered<AttributeDefaultsHook>>> defaults = new HashMap<>();
        private final List<Registered<StripTagsHook>> strip = new ArrayList<>();
        private long order;

        private Builder() {}

        public Builder preRender(PreRenderHook hook) {
            return preRender(DEFAULT_PRIORITY, hook);
        }

        public Builder preRender(int priority, PreRenderHook hook) {
            pre.add(new Registered<>(priority, order++, Objects.requireNonNull(hook, "hook")));
            return this;
        }

        public Builder postRender(PostRenderHook hook) {
            return postRender(DEFAULT_PRIORITY, hook);
        }

        public Builder postRender(int priority, PostRenderHook hook) {
            post.add(new Registered<>(priority, order++, Objects.requireNonNull(hook, "hook")));
            return this;
        }

        public Builder attributeDefaults(String tag, AttributeDefaultsHook hook) {
            return attributeDefaults(DEFAULT_PRIORITY, tag, hook);
        }

        public Builder attributeDefaults(int priority, String tag, AttributeDefaultsHook hook) {
            Objects.requireNonNull(tag, "tag");
            defaults.computeIfAbsent(tag, t -> new ArrayList<>())
                    .add(new Registered<>(priority, order++, Objects.requireNonNull(hook, "hook")));
            return this;
        }

        public Builder stripTags(StripTagsHook hook) {
            return stripTags(DEFAULT_PRIORITY, hook);
        }

        public Builder stripTags(int priority, StripTagsHook hook) {
            strip.add(new Registered<>(priority, order++, Objects.requireNonNull(hook, "hook")));
            return this;
        }

        public ShortcodeHooks build() {
            return new ShortcodeHooks(this);
        }
    }
}
