package org.quarkpress.lib.wpblocks;

import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;

/** Shared generators of non-canonical block markup. */
final class BlockMarkupArbitraries {

    private BlockMarkupArbitraries() {}

    /** Strings glued from delimiter fragments, so that many near-delimiters occur. */
    static Gen<String> commentSoup() {
        Gen<String> fragment = Gens.elements(
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
        return Gens.lists(fragment, 0, 40).map(l -> String.join("", l));
    }

    private static Gen<String> ws() {
        return Gens.strings(" \t\r\n", 1, 3);
    }

    private static Gen<String> name() {
        Gen<String> prefix = Gens.elements("", "core/");
        Gen<String> local = Gens.elements("a", "paragraph", "group", "x-y_2");
        return random -> prefix.next(random) + local.next(random);
    }

    private static Gen<String> delimiter() {
        Gen<String> ws = ws();
        Gen<String> name = name();
        Gen<String> attrs = Gens.elements("", "{}", "{\"k\":\"v\"}");
        Gen<String> end = Gens.elements("-->", "/-->");
        Gen<String> opener = random -> {
            String w1 = ws.next(random);
            String n = name.next(random);
            String w2 = ws.next(random);
            String a = attrs.next(random);
            String w3 = ws.next(random);
            String mid = a.isEmpty() ? w2 : w2 + a + w3;
            return "<!--" + w1 + "wp:" + n + mid + end.next(random);
        };
        Gen<String> closer = random -> "<!--" + ws.next(random) + "/wp:" + name.next(random) + ws.next(random) + "-->";
        Gen<String> text = Gens.elements("", "x", "<p>hi</p>", "\n", " t ");
        return Gens.frequency(Gens.weighted(4, opener), Gens.weighted(3, closer), Gens.weighted(3, text));
    }

    /** Valid openers, voids and closers with varied whitespace and prefixes, interleaved with text. */
    static Gen<String> delimiterVariants() {
        return Gens.lists(delimiter(), 1, 12).map(l -> String.join("", l));
    }
}
