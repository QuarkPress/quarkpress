package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScannerTest {

    private static List<ShortcodeScanner.Hit> scan(String text, String... names) {
        return new ShortcodeScanner(List.of(names)).scan(text);
    }

    private static ShortcodeMatch only(String text, String... names) {
        List<ShortcodeScanner.Hit> hits = scan(text, names);
        assertThat(hits).hasSize(1);
        return hits.get(0).match();
    }

    @Test
    void matchPartsAreThoseOfWordPress() { // §4
        assertThat(only("[inner a=\"1\"]x[/inner]", "inner").parts())
                .containsExactly("[inner a=\"1\"]x[/inner]", "", "inner", " a=\"1\"", "", "x", "");
        assertThat(only("[inner]]", "inner").parts()).containsExactly("[inner]]", "", "inner", "", "", "", "]");
        assertThat(only("[[inner /]", "inner").parts()).containsExactly("[[inner /]", "[", "inner", " ", "/", "", "");
        assertThat(only("[inner]", "inner").parts()).containsExactly("[inner]", "", "inner", "", "", "", "");
    }

    @Test
    void reportsOffsetsAndWhetherAClosingTagWasConsumed() {
        List<ShortcodeScanner.Hit> hits = scan("a [foo]x[/foo] b [foo/] [foo][/foo]", "foo");
        assertThat(hits).hasSize(3);
        assertThat(hits.get(0))
                .extracting(h -> h.start(), h -> h.end(), h -> h.enclosing())
                .containsExactly(2, 14, true);
        assertThat(hits.get(0).match().content()).isEqualTo("x");
        assertThat(hits.get(1))
                .extracting(h -> h.start(), h -> h.end(), h -> h.enclosing())
                .containsExactly(17, 23, false);
        assertThat(hits.get(1).match().selfClosingSlash()).isEqualTo("/");
        assertThat(hits.get(2))
                .extracting(h -> h.start(), h -> h.end(), h -> h.enclosing())
                .containsExactly(24, 35, true);
        assertThat(hits.get(2).match().content()).isEmpty();
    }

    @Test
    void earlierNamesWinWhereBothCouldMatch() { // R3.3a
        assertThat(scan("[x.y] [x]", "x", "x.y").get(0).match())
                .extracting(m -> m.name(), m -> m.attributeText())
                .containsExactly("x", ".y");
        assertThat(scan("[x.y] [x]", "x.y", "x").get(0).match())
                .extracting(m -> m.name(), m -> m.attributeText())
                .containsExactly("x.y", "");
    }

    @Test
    void textThatIsNotAShortcodeIsSkipped() { // R3.3, R3.4, R3.10
        assertThat(scan("[foo", "foo")).isEmpty();
        assertThat(scan("[ foo]", "foo")).isEmpty();
        assertThat(scan("[foobar] [foo-x] [foo_]", "foo")).isEmpty();
        assertThat(scan("[FOO]", "foo")).isEmpty();
        assertThat(scan("[/foo]", "foo")).isEmpty();
        assertThat(only("[[[foo]", "foo").whole()).isEqualTo("[[foo]");
    }

    @Test
    void theFirstExactClosingTagLaterEncloses() { // R3.5, R3.7, R3.9
        assertThat(only("[foo]a[foo]b[/foo]c[/foo]", "foo").content()).isEqualTo("a[foo]b");
        assertThat(only("[foo] text [foo]x[/foo]", "foo").content()).isEqualTo(" text [foo]x");
        assertThat(scan("[foo]x[/FOO]", "foo").get(0).enclosing()).isFalse();
        assertThat(scan("[foo]x[/foo ]", "foo").get(0).enclosing()).isFalse();
        assertThat(only("[foo /]x[/foo]", "foo").whole()).isEqualTo("[foo /]");
        assertThat(only("[foo url=http://x.com/]", "foo").attributeText()).isEqualTo(" url=http://x.com");
        assertThat(scan("[foo a=\"x]y\"]", "foo").get(0).match().attributeText())
                .isEqualTo(" a=\"x");
    }

    @Test
    void replaceRendersEachHitOnceAndCountsThem() {
        ShortcodeScanner scanner = new ShortcodeScanner(List.of("a", "b"));
        ShortcodeScanner.Expansion e =
                scanner.replace("[a] [b]x[/b] [c]", hit -> hit.match().name().toUpperCase());
        assertThat(e.text()).isEqualTo("A B [c]");
        assertThat(e.replacements()).isEqualTo(2);
        assertThat(scanner.replace("none", hit -> "X")).isEqualTo(new ShortcodeScanner.Expansion("none", 0));
    }

    @Test
    void forwardIndexAnswersForwardAndShortBackwardQueries() {
        ForwardIndex index = new ForwardIndex("a]bc]d", ']');
        assertThat(index.next(0)).isEqualTo(1);
        assertThat(index.next(2)).isEqualTo(4);
        assertThat(index.next(1)).isEqualTo(1);
        assertThat(index.next(3)).isEqualTo(4);
        assertThat(index.next(5)).isEqualTo(-1);
        assertThat(index.next(6)).isEqualTo(-1);
    }

    @Test
    void nameTrieFindsMatchingNamesInRegistrationOrder() {
        NameTrie trie = new NameTrie(List.of("x.y", "x", "xy", "x-"));
        assertThat(trie.matchesAt("[x.y]", 1)).containsExactly("x.y", "x");
        assertThat(trie.firstAt("[x.y]", 1)).isEqualTo("x.y");
        assertThat(trie.matchesAt("[xyz]", 1)).isEmpty();
        assertThat(trie.firstAt("[xyz]", 1)).isNull();
        assertThat(trie.matchesAt("[x-]", 1)).containsExactly("x-");
        assertThat(trie.matchesAt("[x", 1)).containsExactly("x");
        assertThat(trie.longest()).isEqualTo(3);
    }

    @Test
    void closingTagsIndexesExactClosersOfTheGivenNames() {
        String text = "[/foo] [/foo ] [/FOO] [/bar] [/[/foo] [/foo]";
        ClosingTags closers = new ClosingTags(text, Set.of("foo"), 3);
        assertThat(closers.first("foo", 0)).isEqualTo(0);
        assertThat(closers.first("foo", 1)).isEqualTo(31);
        assertThat(closers.first("foo", 32)).isEqualTo(38);
        assertThat(closers.first("foo", 39)).isEqualTo(-1);
        assertThat(closers.first("bar", 0)).isEqualTo(-1);
    }

    @Test
    void hostileInputsScanInLinearTime() {
        String openers = "[foo]".repeat(100_000);
        assertLinear(openers, "foo", 100_000);
        assertLinear("[foo ".repeat(100_000), "foo", 0);
        assertLinear("[[".repeat(100_000), "foo", 0);
        assertLinear("[/foo".repeat(100_000), "foo", 0);
        assertLinear("[foo a=1" + "[foo".repeat(100_000), "foo", 0);
        assertLinear("[foo]" + "[/fo]".repeat(100_000), "foo", 1);
    }

    private static void assertLinear(String text, String name, int expectedHits) {
        ShortcodeScanner scanner = new ShortcodeScanner(List.of(name));
        scanner.scan(text);
        long start = System.nanoTime();
        List<ShortcodeScanner.Hit> hits = scanner.scan(text);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(hits).hasSize(expectedHits);
        assertThat(millis).as("scan time in ms").isLessThan(1_000);
    }
}
