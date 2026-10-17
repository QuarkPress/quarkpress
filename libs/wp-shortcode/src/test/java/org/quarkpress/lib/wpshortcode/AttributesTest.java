package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quarkpress.lib.wpshortcode.ShortcodeAttributes.Entry;
import org.quarkpress.lib.wpshortcode.ShortcodeAttributes.Key;

class AttributesTest {

    private static ShortcodeAttributes parse(String text) {
        return ShortcodeAttributes.parse(text);
    }

    private static Entry named(String name, String value) {
        return new Entry(new Key.Name(name), value);
    }

    private static Entry indexed(long index, String value) {
        return new Entry(new Key.Index(index), value);
    }

    @Test
    void invisibleSpacesSeparateTokens() { // R6.1
        assertThat(parse("a=x\u00a0b=y").entries()).containsExactly(named("a", "x"), named("b", "y"));
        assertThat(parse("a=x\u200bb=y").entries()).containsExactly(named("a", "x"), named("b", "y"));
        assertThat(parse("a=\"non\u00a0breaking\"").entries()).containsExactly(named("a", "non breaking"));
        assertThat(parse("a=x\u00a0\u200b\u00a0b=y").entries()).containsExactly(named("a", "x"), named("b", "y"));
        assertThat(parse("a=x\u2003b=y").entries()).containsExactly(named("a", "x\u2003b=y"));
    }

    @Test
    void namedValues() { // R6.3, R6.4
        assertThat(parse("a=\"1\" b='2' c=3").entries())
                .containsExactly(named("a", "1"), named("b", "2"), named("c", "3"));
        assertThat(parse("A=\"x\" Ab='y'").entries()).containsExactly(named("a", "x"), named("ab", "y"));
        assertThat(parse(" a = \"1\"  b =  2 ").entries()).containsExactly(named("a", "1"), named("b", "2"));
        assertThat(parse("a = 'x' b = \"y\"").entries()).containsExactly(named("a", "x"), named("b", "y"));
        assertThat(parse("a=1 a=2").entries()).containsExactly(named("a", "2"));
        assertThat(parse("data-x=\"1\" a-b=2 a_b=3").entries())
                .containsExactly(named("data-x", "1"), named("a-b", "2"), named("a_b", "3"));
        assertThat(parse("a=\"it's\" b='say \"hi\"'").entries())
                .containsExactly(named("a", "it's"), named("b", "say \"hi\""));
        assertThat(parse("a=1=2").entries()).containsExactly(named("a", "1=2"));
        assertThat(parse("a= b").entries()).containsExactly(named("a", "b"));
        assertThat(parse("a=[x]").entries()).containsExactly(named("a", "[x]"));
        assertThat(parse("a=\"\" b='' c").entries()).containsExactly(named("a", ""), named("b", ""), indexed(0L, "c"));
        assertThat(parse("a=\"line\none\"").entries()).containsExactly(named("a", "line\none"));
    }

    @Test
    void positionalValues() { // R6.5
        assertThat(parse("a=\"x y\" foo \"bar baz\" 'qux q' last").entries())
                .containsExactly(
                        named("a", "x y"),
                        indexed(0L, "foo"),
                        indexed(1L, "bar baz"),
                        indexed(2L, "qux q"),
                        indexed(3L, "last"));
        assertThat(parse(" a=1 b").entries()).containsExactly(named("a", "1"), indexed(0L, "b"));
        assertThat(parse("\"0\"").entries()).containsExactly(indexed(0L, "0"));
        assertThat(parse("0").entries()).containsExactly(indexed(0L, "0"));
        assertThat(parse("\"\"").entries()).isEmpty();
        assertThat(parse("''").entries()).isEmpty();
        assertThat(parse("\"\" x '' y").entries()).containsExactly(indexed(0L, "x"), indexed(1L, "y"));
    }

    @Test
    void numericNamesFollowPhpArrayKeys() { // R6.6
        assertThat(parse("5=a b").entries()).containsExactly(indexed(5L, "a"), indexed(6L, "b"));
        assertThat(parse("-1=a b").entries()).containsExactly(indexed(-1L, "a"), indexed(0L, "b"));
        assertThat(parse("x 1=a y").entries()).containsExactly(indexed(0L, "x"), indexed(1L, "a"), indexed(2L, "y"));
        assertThat(parse("1=one -=dash").entries()).containsExactly(indexed(1L, "one"), named("-", "dash"));
        assertThat(parse("0=\"x\" y").entries()).containsExactly(indexed(0L, "y"));
        assertThat(parse("0=x").entries()).isEmpty();
        assertThat(parse("00=x").entries()).containsExactly(named("00", "x"));
        assertThat(parse("-5=a b").entries()).containsExactly(indexed(-5L, "a"), indexed(-4L, "b"));
        assertThat(parse("-0=x y").entries()).containsExactly(named("-0", "x"), indexed(0L, "y"));
        assertThat(parse("9223372036854775808=a b").entries())
                .containsExactly(named("9223372036854775808", "a"), indexed(0L, "b"));
        assertThat(parse("-9223372036854775808=a b").entries())
                .containsExactly(indexed(-9223372036854775808L, "a"), indexed(-9223372036854775807L, "b"));
        assertThat(parse("9223372036854775806=a b").entries())
                .containsExactly(indexed(9223372036854775806L, "a"), indexed(9223372036854775807L, "b"));
        assertThat(parse("5=a 3=b c").entries()).containsExactly(indexed(5L, "a"), indexed(3L, "b"), indexed(6L, "c"));
        assertThat(parse("1=a 1=b c").entries()).containsExactly(indexed(1L, "b"), indexed(2L, "c"));
    }

    @Test
    void backslashEscapes() { // R6.7
        assertThat(parse("a=\"\\n\\t\\r\\v\\f\\a\\b\"").entries())
                .containsExactly(named("a", "\n\t\r\u000b\f\u0007\b"));
        assertThat(parse("a=\"\\x41\\x42\"").entries()).containsExactly(named("a", "AB"));
        assertThat(parse("a=\"\\x4\"").entries()).containsExactly(named("a", "\u0004"));
        assertThat(parse("a=\"\\101\"").entries()).containsExactly(named("a", "A"));
        assertThat(parse("a=\"\\0\"").entries()).containsExactly(named("a", "\u0000"));
        assertThat(parse("a=\"\\q\"").entries()).containsExactly(named("a", "q"));
        assertThat(parse("a=\"\\e\"").entries()).containsExactly(named("a", "e"));
        assertThat(parse("a=\"\\\\\"").entries()).containsExactly(named("a", "\\"));
        assertThat(parse("a=x\\").entries()).containsExactly(named("a", "x\\"));
        assertThat(parse("a=\"\\xc3\\xa9\"").entries()).containsExactly(named("a", "\u00E9"));
        assertThat(parse("a=\"\\400\"").entries()).containsExactly(named("a", "\u0000"));
        assertThat(parse("a=\"\\x\"").entries()).containsExactly(named("a", "x"));
        assertThat(parse("a=\"\\xg\"").entries()).containsExactly(named("a", "xg"));
        assertThat(parse("a=\"\\1234\"").entries()).containsExactly(named("a", "S4"));
        assertThat(parse("a=\\\u00FC").entries()).containsExactly(named("a", "\u00FC"));
        assertThat(parse("x\\n").entries()).containsExactly(indexed(0L, "x\n"));
        assertThat(parse("a=\"\\\"quoted\\\"\"").entries()).containsExactly(indexed(0L, "a=\"\"quoted\"\""));
        assertThat(parse("a='it\\'s'").entries()).containsExactly(indexed(0L, "a='it's'"));
        // PHP keeps the raw byte 0xFF; decoded as UTF-8 (decision 2) it is U+FFFD
        assertThat(parse("a=\"\\xff\"").entries()).containsExactly(named("a", "\ufffd"));
        assertThat(parse("a=\"\\777\"").entries()).containsExactly(named("a", "\ufffd"));
    }

    @Test
    void malformedTextFallsBackToPositionals() { // R6.8
        assertThat(parse("a=\"1\"b=\"2\"").entries()).containsExactly(indexed(0L, "a=\"1\"b=\"2\""));
        assertThat(parse("a=\"x\"y").entries()).containsExactly(indexed(0L, "a=\"x\"y"));
        assertThat(parse("a='x'\"y\"").entries()).containsExactly(indexed(0L, "a='x'\"y\""));
        assertThat(parse("a=\"unterminated b=2").entries())
                .containsExactly(indexed(0L, "a=\"unterminated"), named("b", "2"));
        assertThat(parse("=x").entries()).containsExactly(indexed(0L, "=x"));
        assertThat(parse("a=").entries()).containsExactly(indexed(0L, "a="));
        assertThat(parse("\"").entries()).containsExactly(indexed(0L, "\""));
        assertThat(parse("%$#").entries()).containsExactly(indexed(0L, "%$#"));
        assertThat(parse("a=\"1\"/").entries()).containsExactly(indexed(0L, "a=\"1\"/"));
        assertThat(parse("\u00FC=\"u\"").entries()).containsExactly(indexed(0L, "\u00FC=\"u\""));
        assertThat(parse("A\u00C4=1").entries()).containsExactly(indexed(0L, "A\u00C4=1"));
        assertThat(parse("a b=c").entries()).containsExactly(indexed(0L, "a"), named("b", "c"));
    }

    @Test
    void allSixAsciiSpacesSeparate() { // R6.9
        assertThat(parse("a=\"x\"\fb=1").entries()).containsExactly(named("a", "x"), named("b", "1"));
        assertThat(parse("a=1\u000bb=2").entries()).containsExactly(named("a", "1"), named("b", "2"));
        assertThat(parse("a=1\rb=2").entries()).containsExactly(named("a", "1"), named("b", "2"));
    }

    @Test
    void blankTextHasNoAttributes() { // R6.10
        assertThat(parse("  ").entries()).isEmpty();
        assertThat(parse("").entries()).isEmpty();
    }

    @Test
    void valuesWithUnclosedTagsBecomeEmpty() { // R6.11
        assertThat(parse("a=\"<b>bold</b>\" b=\"<b\" c=\"x>y\" d=\"<a><\" e=\"<!--x-->\"")
                        .entries())
                .containsExactly(
                        named("a", "<b>bold</b>"),
                        named("b", ""),
                        named("c", "x>y"),
                        named("d", ""),
                        named("e", "<!--x-->"));
        assertThat(parse("<b>x</b> <b").entries()).containsExactly(indexed(0L, "<b>x</b>"), indexed(1L, ""));
        assertThat(parse("a=\"<a<b>\"").entries()).containsExactly(named("a", "<a<b>"));
        assertThat(parse("a=\"x>y<z\"").entries()).containsExactly(named("a", ""));
        assertThat(parse("a=\"<\"").entries()).containsExactly(named("a", ""));
    }

    @Test
    void invalidUtf16GivesNoAttributes() { // R6.2: PHP's "b=2 c=\"\xc3\x28\"" gives [] too
        assertThat(parse("b=2 c=\"\uD800\"").entries()).isEmpty();
        assertThat(parse("a=1 \uDC00").entries()).isEmpty();
        assertThat(parse("a=\"\uD83D\uDE00\"").entries()).containsExactly(named("a", "\uD83D\uDE00"));
    }
}
