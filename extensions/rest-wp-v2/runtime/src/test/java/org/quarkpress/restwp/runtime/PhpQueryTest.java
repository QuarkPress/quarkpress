package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Expected values are PHP 8.4's {@code parse_str} answers. */
class PhpQueryTest {

    @Test
    void parsesLikePhp() {
        assertThat(PhpQuery.parse("a=1&a=2").toString()).isEqualTo("{\"a\":\"2\"}");
        assertThat(PhpQuery.parse("include[]=1&include[]=2").toString())
                .isEqualTo("{\"include\":{\"0\":\"1\",\"1\":\"2\"}}");
        assertThat(PhpQuery.parse("a[x]=1&a[]=2").toString()).isEqualTo("{\"a\":{\"x\":\"1\",\"0\":\"2\"}}");
        assertThat(PhpQuery.parse("a.b=1&c+d=2&e[f.g]=3").toString())
                .isEqualTo("{\"a_b\":\"1\",\"c_d\":\"2\",\"e\":{\"f.g\":\"3\"}}");
        assertThat(PhpQuery.parse("s=a+b%2Cc&flag").toString()).isEqualTo("{\"s\":\"a b,c\",\"flag\":\"\"}");
    }

    @Test
    void namesFollowPhpsRules() {
        assertThat(PhpQuery.parse("  a b=1&.a=2&_=3").toString()).isEqualTo("{\"a_b\":\"1\",\"_a\":\"2\",\"_\":\"3\"}");
        // an unclosed bracket in the name: the whole name is a plain one
        assertThat(PhpQuery.parse("a[ b.c=1&k[x").toString()).isEqualTo("{\"a__b_c\":\"1\",\"k_x\":\"\"}");
        // no name before the bracket, or an empty name: dropped
        assertThat(PhpQuery.parse("[a]=1&=x&[x").toString()).isEqualTo("{}");
        // encoded brackets are brackets; a NUL ends the name
        assertThat(PhpQuery.parse("a%5Bb%5D=1&c%00d=2").toString()).isEqualTo("{\"a\":{\"b\":\"1\"},\"c\":\"2\"}");
        assertThat(PhpQuery.parse("a]b=1&a\\x=2").toString()).isEqualTo("{\"a]b\":\"1\",\"a\\\\x\":\"2\"}");
        assertThat(PhpQuery.parse("&&a=1=2&&").toString()).isEqualTo("{\"a\":\"1=2\"}");
    }

    @Test
    void indexesFollowPhpsRules() {
        assertThat(PhpQuery.parse("a[5]=1&a[]=2").toString()).isEqualTo("{\"a\":{\"5\":\"1\",\"6\":\"2\"}}");
        assertThat(PhpQuery.parse("a[-5]=1&a[]=2").toString()).isEqualTo("{\"a\":{\"-5\":\"1\",\"-4\":\"2\"}}");
        assertThat(PhpQuery.parse("a[05]=1&a[-0]=2&a[]=3").toString())
                .isEqualTo("{\"a\":{\"05\":\"1\",\"-0\":\"2\",\"0\":\"3\"}}");
        assertThat(PhpQuery.parse("a[1]=1&a[0]=2&a[]=3").toString())
                .isEqualTo("{\"a\":{\"1\":\"1\",\"0\":\"2\",\"2\":\"3\"}}");
        assertThat(PhpQuery.parse("a[]=1&a[]=2&a[1]=x&a[]=3").toString())
                .isEqualTo("{\"a\":{\"0\":\"1\",\"1\":\"x\",\"2\":\"3\"}}");
        // after the largest integer key nothing more can be appended
        assertThat(PhpQuery.parse("a[9223372036854775807]=1&a[]=2&b=1").toString())
                .isEqualTo("{\"a\":{\"9223372036854775807\":\"1\"},\"b\":\"1\"}");
        // [ ] with one white-space character appends; other text, white space included, is the key
        assertThat(PhpQuery.parse("a[ ]=1&a[%09]=2&a[  ]=3&a[ 1]=4").toString())
                .isEqualTo("{\"a\":{\"0\":\"1\",\"1\":\"2\",\"  \":\"3\",\" 1\":\"4\"}}");
        // text after a closing bracket that is not '[' is ignored, as is a nested bracket that never closes
        assertThat(PhpQuery.parse("a[b]x[c]=1&d[x][=2&e[b[c]=3&f[b]]=4").toString())
                .isEqualTo("{\"a\":{\"b\":\"1\"},\"d\":{\"x\":\"2\"},\"e\":{\"b[c\":\"3\"},\"f\":{\"b\":\"4\"}}");
        assertThat(PhpQuery.parse("x[a][]=1&x[a][]=2").toString())
                .isEqualTo("{\"x\":{\"a\":{\"0\":\"1\",\"1\":\"2\"}}}");
    }

    @Test
    void laterValuesReplaceEarlierOnesOfAnotherShape() {
        assertThat(PhpQuery.parse("a[]=1&a=2").toString()).isEqualTo("{\"a\":\"2\"}");
        assertThat(PhpQuery.parse("a=2&a[]=1").toString()).isEqualTo("{\"a\":{\"0\":\"1\"}}");
        assertThat(PhpQuery.parse("a[b]=1&a[b][c]=2").toString()).isEqualTo("{\"a\":{\"b\":{\"c\":\"2\"}}}");
        assertThat(PhpQuery.parse("a[b]=1&a=x&a[c]=2").toString()).isEqualTo("{\"a\":{\"c\":\"2\"}}");
    }

    @Test
    void valuesDecodeLikeUrldecode() {
        String eAcute = new String(Character.toChars(0xE9));
        String replacement = new String(Character.toChars(0xFFFD));
        assertThat(PhpQuery.parse("a=%zz%4&b=%C3%A9&c=%C3&d=x%00y").get("a").textValue())
                .isEqualTo("%zz%4");
        assertThat(PhpQuery.parse("b=%C3%A9").get("b").textValue()).isEqualTo(eAcute);
        assertThat(PhpQuery.parse("c=%C3").get("c").textValue()).isEqualTo(replacement);
        assertThat(PhpQuery.parse("d=x%00y").get("d").textValue()).isEqualTo("x\u0000y");
        // the HTTP layer hands raw bytes over one char per byte: a raw UTF-8 e-acute arrives as two chars
        assertThat(PhpQuery.parse("e=caf" + new String(new char[] {0xC3, 0xA9}))
                        .get("e")
                        .textValue())
                .isEqualTo("caf" + eAcute);
        assertThat(PhpQuery.rawDecode("/a+b%2Fc")).isEqualTo("/a+b/c");
    }

    @Test
    void phpsInputLimitsApply() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 999; i++) {
            many.append("v").append(i).append("=1&");
        }
        assertThat(PhpQuery.parse(many + "last=1").has("last")).isTrue();
        assertThat(PhpQuery.parse(many + "=x&last=1").has("last")).isFalse();
        // empty pairs do not count
        assertThat(PhpQuery.parse("&".repeat(1500) + "a=1").has("a")).isTrue();
        assertThat(PhpQuery.parse("a" + "[x]".repeat(64) + "=1").has("a")).isTrue();
        assertThat(PhpQuery.parse("a" + "[x]".repeat(65) + "=1").has("a")).isFalse();
    }

    @Test
    void pairsWithoutAnEqualsSignAreSplitInLinearWork() {
        // 999 pairs without '=' before one long pair: each pair is scanned once, never the rest of the input
        String body = "a&".repeat(999) + "b" + "x".repeat(1_000_000);
        long[] scanned = new long[1];
        var parsed = PhpQuery.parse(body, scanned);
        assertThat(scanned[0]).isLessThanOrEqualTo(body.length());
        assertThat(parsed.size()).isEqualTo(2);
        assertThat(parsed.get("a").textValue()).isEmpty();
        assertThat(parsed.get("b" + "x".repeat(1_000_000)).textValue()).isEmpty();
        // the same with the long pair holding the only '=' at its end
        String late = "a&".repeat(999) + "x".repeat(1_000_000) + "=v";
        long[] lateScanned = new long[1];
        assertThat(PhpQuery.parse(late, lateScanned).get("x".repeat(1_000_000)).textValue())
                .isEqualTo("v");
        assertThat(lateScanned[0]).isLessThanOrEqualTo(late.length());
        // pairs beyond PHP's 1000 are never registered, and the work still stays within the input
        String more = "a=1&".repeat(1500) + "z=2";
        long[] moreScanned = new long[1];
        assertThat(PhpQuery.parse(more, moreScanned).has("z")).isFalse();
        assertThat(moreScanned[0]).isLessThanOrEqualTo(more.length());
    }
}
