package org.quarkpress.lib.wpblocks;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/** Shared generators of non-canonical block markup. */
final class BlockMarkupArbitraries {

    private BlockMarkupArbitraries() {}

    /** Strings glued from delimiter fragments, so that many near-delimiters occur. */
    static Arbitrary<String> commentSoup() {
        Arbitrary<String> fragment = Arbitraries.of(
                "<!--",
                " wp:",
                " /wp:",
                "a",
                "b/c",
                "core/x",
                "{",
                "}",
                "\"",
                "\\",
                ":",
                "1",
                " ",
                "\n",
                "-->",
                "/-->",
                "<p>",
                "--",
                "<!-- wp:a -->",
                "<!-- /wp:a -->",
                "<!-- wp:a /-->",
                "<!-- wp:a {\"k\":\"}\"} -->",
                "<!--  wp:a  -->",
                "<!--\nwp:a\n/-->",
                "<!--\twp:core/a\t-->",
                "<!-- wp:core/a {\"k\":1}\r\n/-->",
                "<!--   /wp:core/a\n-->",
                "<!-- wp:acme/x-y_2 {} -->",
                "<!-- wp:A -->");
        return fragment.list().ofMaxSize(40).map(l -> String.join("", l));
    }

    private static Arbitrary<String> ws() {
        return Arbitraries.strings()
                .withChars(' ', '\t', '\r', '\n')
                .ofMinLength(1)
                .ofMaxLength(3);
    }

    private static Arbitrary<String> name() {
        return Combinators.combine(Arbitraries.of("", "core/"), Arbitraries.of("a", "paragraph", "group", "x-y_2"))
                .as((p, n) -> p + n);
    }

    private static Arbitrary<String> delimiter() {
        Arbitrary<String> attrs = Arbitraries.of("", "{}", "{\"k\":\"v\"}");
        Arbitrary<String> opener = Combinators.combine(ws(), name(), ws(), attrs, ws(), Arbitraries.of("-->", "/-->"))
                .as((w1, n, w2, a, w3, end) -> {
                    String mid = a.isEmpty() ? w2 : w2 + a + w3;
                    return "<!--" + w1 + "wp:" + n + mid + end;
                });
        Arbitrary<String> closer =
                Combinators.combine(ws(), name(), ws()).as((w1, n, w2) -> "<!--" + w1 + "/wp:" + n + w2 + "-->");
        Arbitrary<String> text = Arbitraries.of("", "x", "<p>hi</p>", "\n", " t ");
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(4, opener), net.jqwik.api.Tuple.of(3, closer), net.jqwik.api.Tuple.of(3, text));
    }

    /** Valid openers, voids and closers with varied whitespace and prefixes, interleaved with text. */
    static Arbitrary<String> delimiterVariants() {
        Arbitrary<List<String>> parts = delimiter().list().ofMinSize(1).ofMaxSize(12);
        return parts.map(l -> String.join("", l));
    }
}
