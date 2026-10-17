package org.quarkpress.lib.wpshortcode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The parity driver's profiles and hooks, in Java. Keep both files in step. */
final class Profiles {

    /** The driver's QP_PAIRS. */
    static final Map<String, String> PAIRS;

    static {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("a", "A");
        pairs.put("b", "B");
        pairs.put("0", "Z");
        pairs.put("data-x", "D");
        PAIRS = Collections.unmodifiableMap(pairs);
    }

    private Profiles() {}

    static ShortcodeRegistry registry(String profile) {
        return switch (profile) {
            case "main" -> main();
            case "x-first" ->
                ShortcodeRegistry.builder()
                        .add("x", Echo.HANDLER)
                        .add("x.y", Echo.HANDLER)
                        .build();
            case "xy-first" ->
                ShortcodeRegistry.builder()
                        .add("x.y", Echo.HANDLER)
                        .add("x", Echo.HANDLER)
                        .build();
            case "empty" -> ShortcodeRegistry.builder().build();
            default -> throw new IllegalArgumentException("unknown profile " + profile);
        };
    }

    private static ShortcodeRegistry main() {
        ShortcodeRegistry.Builder b = ShortcodeRegistry.builder();
        for (String tag : List.of("foo", "b", "foo-bar", "foo_bar", "x.y", "ü", "gallery")) {
            b.add(tag, Echo.HANDLER);
        }
        return b.add("inner", (s, c) -> "I")
                .add("outer", (s, c) -> "<o>" + c.expand(s.content()) + "</o>")
                .add("quiet", (s, c) -> "<o>" + s.content() + "</o>")
                .add("raw", (s, c) -> "C=" + s.content())
                .add("emit", (s, c) -> "[inner]")
                .add("ent", (s, c) -> "&#91;inner&#93;")
                .add("nul", (s, c) -> null)
                .add("cls", (s, c) -> "red")
                .add("url", (s, c) -> "http://ex.com/?q=\"x\"&y=<z>")
                .add("js", (s, c) -> "javascript:alert(1)")
                .add("attr", (s, c) -> "title=\"hi\"")
                .add("amp", (s, c) -> "a &amp; b &copy; &apos; &bogus; &#65; &#x41;")
                .build();
    }

    static ShortcodeHooks hooks(List<String> names) {
        ShortcodeHooks.Builder b = ShortcodeHooks.builder().attributeDefaults("gal", (out, defaults, atts, tag) -> {
            Map<String, String> copy = new LinkedHashMap<>(out);
            copy.put("extra", "E");
            return copy;
        });
        for (String name : names) {
            switch (name) {
                case "pre-inner" -> b.preRender((s, current) -> s.tag().equals("inner") ? Optional.of("PRE") : current);
                case "post-wrap" -> b.postRender((out, s) -> "<" + s.tag() + ":" + out + ">");
                case "strip-foo" -> b.stripTags((tags, content) -> List.of("foo", "unreg"));
                default -> throw new IllegalArgumentException("unknown hook " + name);
            }
        }
        return b.build();
    }
}
