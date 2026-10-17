package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TagPrefilterTest {

    private static List<String> active(String content, String... names) {
        return TagPrefilter.active(content, List.of(names));
    }

    @Test
    void keepsTheOrderOfTheNames() {
        assertThat(active("[b] [foo] [nope]", "foo", "b", "c")).containsExactly("foo", "b");
    }

    @Test
    void aNameMustBeAWholeCandidate() { // R2.3a
        assertThat(active("[foo.x]", "foo")).isEmpty();
        assertThat(active("[foo.x] [foo]", "foo")).containsExactly("foo");
        assertThat(active("[foo,] [foo:x] [foo!]", "foo")).isEmpty();
        assertThat(active("[foo😀]", "foo")).isEmpty();
        assertThat(active("[foo\u00a0x]", "foo")).isEmpty(); // no-break space is a name char
    }

    @Test
    void spacesControlsAndSevenPunctuationCharsEndACandidate() { // R2.3b, R2.3c
        assertThat(active("[foo=1]", "foo")).containsExactly("foo");
        assertThat(active("[foo&x]", "foo")).containsExactly("foo");
        assertThat(active("[foo/]", "foo")).containsExactly("foo");
        assertThat(active("[foo<b]", "foo")).containsExactly("foo");
        assertThat(active("[foo\u0001]", "foo")).containsExactly("foo");
        assertThat(active("[foo\tx]", "foo")).containsExactly("foo");
        assertThat(active("[foo x]", "foo")).containsExactly("foo");
        assertThat(active("[foo[foo]", "foo")).containsExactly("foo");
        assertThat(active("[/foo]", "foo")).isEmpty();
        assertThat(active("[]", "foo")).isEmpty();
    }
}
