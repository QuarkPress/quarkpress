package org.quarkpress.lib.wpshortcode;

/** The registries of the behavior reference's examples; each mirrors a registry used to produce expected values. */
final class Registries {

    private Registries() {}

    /** foo, b, foo-bar, foo_bar, x.y and \u00fc, all with the echo handler (behavior reference \u00a70). */
    static ShortcodeRegistry echo() {
        ShortcodeRegistry.Builder b = ShortcodeRegistry.builder();
        for (String tag : new String[] {"foo", "b", "foo-bar", "foo_bar", "x.y", "\u00fc"}) {
            b.add(tag, Echo.HANDLER);
        }
        return b.build();
    }

    /** The R7/R8 handlers. */
    static ShortcodeRegistry callbacks() {
        return ShortcodeRegistry.builder()
                .add("inner", (s, c) -> "I")
                .add("outer", (s, c) -> "<o>" + c.expand(s.content()) + "</o>")
                .add("emit", (s, c) -> "[inner]")
                .add("ent", (s, c) -> "&#91;inner&#93;")
                .add("quiet", (s, c) -> "<o>" + s.content() + "</o>")
                .add("raw", (s, c) -> "C=" + s.content())
                .add("nul", (s, c) -> null)
                .build();
    }

    /** The R12/R13 handlers. */
    static ShortcodeRegistry html() {
        return ShortcodeRegistry.builder()
                .add("url", (s, c) -> "http://ex.com/?q=\"x\"&y=<z>")
                .add("js", (s, c) -> "javascript:alert(1)")
                .add("cls", (s, c) -> "red")
                .add("attr", (s, c) -> "title=\"hi\"")
                .add("foo", Echo.HANDLER)
                .add("ent", (s, c) -> "a &amp; b &copy; &apos; &bogus; &#65; &#x41;")
                .add("nul", (s, c) -> "a\u0001b")
                .build();
    }

    /** Every name renders {@code output}. */
    static ShortcodeRegistry constant(String output, String... names) {
        ShortcodeRegistry.Builder b = ShortcodeRegistry.builder();
        for (String name : names) {
            b.add(name, (s, c) -> output);
        }
        return b.build();
    }
}
