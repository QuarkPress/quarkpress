package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.restwp.runtime.SchemaValidatorTest.array;
import static org.quarkpress.restwp.runtime.SchemaValidatorTest.bool;
import static org.quarkpress.restwp.runtime.SchemaValidatorTest.integer;
import static org.quarkpress.restwp.runtime.SchemaValidatorTest.json;
import static org.quarkpress.restwp.runtime.SchemaValidatorTest.object;
import static org.quarkpress.restwp.runtime.SchemaValidatorTest.schema;
import static org.quarkpress.restwp.runtime.SchemaValidatorTest.string;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Expected values are WordPress 7.1.3's sanitized values for the same input and schema (PHP 8.4). */
class SchemaSanitizerTest {

    @Test
    void sanitizesToTheType() {
        assertThat(sanitize("5.0", integer())).isEqualTo(IntNode.valueOf(5));
        assertThat(sanitize("False", bool())).isEqualTo(BooleanNode.FALSE);
        assertThat(sanitize("yes", bool())).isEqualTo(BooleanNode.TRUE);
        assertThat(sanitize("1, 2,,3", array(integer())).toString()).isEqualTo("[1,2,3]");
        assertThat(sanitize("", object()).toString()).isEqualTo("{}");
        assertThat(sanitize(json("{\"a\":1,\"x\":2}"), objectWithout("a")).toString())
                .isEqualTo("{\"a\":1}");
    }

    @Test
    void integersAreCastAsPhpCasts() {
        Map<String, Long> casts = Map.ofEntries(
                Map.entry(" 5 ", 5L),
                Map.entry("1e400", 0L),
                Map.entry("-1e400", 0L),
                Map.entry("1e1", 10L),
                Map.entry("5.9", 5L),
                Map.entry("-5.9", -5L),
                Map.entry("9223372036854775808", Long.MAX_VALUE),
                Map.entry("-9223372036854775809", Long.MIN_VALUE),
                Map.entry("1e19", Long.MAX_VALUE),
                Map.entry("x", 0L),
                Map.entry("", 0L),
                Map.entry("0x1A", 0L),
                Map.entry("12abc", 12L),
                Map.entry("\n12", 12L),
                Map.entry("1e5abc", 100_000L),
                Map.entry("  -3.7e1z", -37L),
                Map.entry("-.5e1", -5L),
                Map.entry("0012", 12L));
        casts.forEach((text, expected) ->
                assertThat(sanitize(text, integer()).longValue()).as(text).isEqualTo(expected));
        assertThat(sanitize("9223372036854775807", integer())).isEqualTo(LongNode.valueOf(Long.MAX_VALUE));
        // numbers: truncation, and modulo 2^64 beyond the range
        assertThat(sanitize(json("5.7"), integer())).isEqualTo(IntNode.valueOf(5));
        assertThat(sanitize(json("1e30"), integer()).longValue()).isEqualTo(5076964154930102272L);
        assertThat(sanitize(json("1e19"), integer()).longValue()).isEqualTo(-8446744073709551616L);
        assertThat(sanitize(json("-1e19"), integer()).longValue()).isEqualTo(8446744073709551616L);
        assertThat(sanitize(json("9.2233720368547758E18"), integer()).longValue())
                .isEqualTo(Long.MIN_VALUE);
        assertThat(sanitize(json("1.8446744073709552E19"), integer()).longValue())
                .isZero();
        assertThat(sanitize(json("true"), integer())).isEqualTo(IntNode.valueOf(1));
        assertThat(sanitize(NullNode.getInstance(), integer())).isEqualTo(IntNode.valueOf(0));
        assertThat(sanitize(json("[1]"), integer())).isEqualTo(IntNode.valueOf(1));
    }

    @Test
    void numbersBooleansAndStrings() {
        ObjectNode number = schema("{\"type\":\"number\"}");
        assertThat(sanitize("5.0", number)).isEqualTo(DoubleNode.valueOf(5.0));
        assertThat(sanitize("1e400", number)).isEqualTo(DoubleNode.valueOf(Double.POSITIVE_INFINITY));
        assertThat(sanitize("x", number)).isEqualTo(DoubleNode.valueOf(0.0));
        assertThat(sanitize(json("true"), number)).isEqualTo(DoubleNode.valueOf(1.0));
        for (String falsy : List.of("False", "", "0", "FALSE")) {
            assertThat(sanitize(falsy, bool())).as(falsy).isEqualTo(BooleanNode.FALSE);
        }
        for (String truthy : List.of("false ", "0.0", "00", "true", "yes")) {
            assertThat(sanitize(truthy, bool())).as(truthy).isEqualTo(BooleanNode.TRUE);
        }
        assertThat(sanitize(json("0"), bool())).isEqualTo(BooleanNode.FALSE);
        assertThat(sanitize(json("[]"), bool())).isEqualTo(BooleanNode.FALSE);
        assertThat(sanitize(json("[0]"), bool())).isEqualTo(BooleanNode.TRUE);
        assertThat(sanitize(NullNode.getInstance(), bool())).isEqualTo(BooleanNode.FALSE);
        assertThat(sanitize(json("5"), string())).isEqualTo(TextNode.valueOf("5"));
        assertThat(sanitize(json("5.5"), string())).isEqualTo(TextNode.valueOf("5.5"));
        assertThat(sanitize(json("0.30000000000000004"), string())).isEqualTo(TextNode.valueOf("0.3"));
        assertThat(sanitize(json("1e25"), string())).isEqualTo(TextNode.valueOf("1.0E+25"));
        assertThat(sanitize(json("true"), string())).isEqualTo(TextNode.valueOf("1"));
        assertThat(sanitize(json("false"), string())).isEqualTo(TextNode.valueOf(""));
        assertThat(sanitize(NullNode.getInstance(), string())).isEqualTo(TextNode.valueOf(""));
        assertThat(sanitize(json("[1]"), string())).isEqualTo(TextNode.valueOf("Array"));
    }

    @Test
    void arraysObjectsAndSeveralTypes() {
        assertThat(sanitize(" 1 2\t3\n4\r5\u000b6\f7\u00008", array(string())).toString())
                .isEqualTo("[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\\u00008\"]");
        assertThat(sanitize(json("{\"3\":\"1\",\"5\":\"2\"}"), array(integer())).toString())
                .isEqualTo("[1,2]");
        assertThat(sanitize(json("5"), array(integer())).toString()).isEqualTo("[5]");
        assertThat(sanitize(json("true"), array(null)).toString()).isEqualTo("[\"1\"]");
        assertThat(sanitize(json("false"), array(null)).toString()).isEqualTo("[]");
        assertThat(sanitize(NullNode.getInstance(), array(null)).toString()).isEqualTo("[]");
        assertThat(sanitize("x", object()).toString()).isEqualTo("{}");
        assertThat(sanitize(json("[1,2]"), object()).toString()).isEqualTo("{\"0\":1,\"1\":2}");
        ObjectNode typed = schema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}},"
                + "\"additionalProperties\":{\"type\":\"integer\"}}");
        assertThat(sanitize(json("{\"a\":\"1\",\"x\":\"2\"}"), typed).toString())
                .isEqualTo("{\"a\":1,\"x\":2}");
        ObjectNode open = schema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}}");
        assertThat(sanitize(json("{\"a\":\"1\",\"x\":\"2\"}"), open).toString()).isEqualTo("{\"a\":1,\"x\":\"2\"}");
        assertThat(sanitize("x", schema("{\"type\":[\"boolean\",\"array\"],\"items\":{\"type\":\"string\"}}"))
                        .toString())
                .isEqualTo("[\"x\"]");
        assertThat(sanitize("", schema("{\"type\":[\"integer\",\"string\"]}"))).isEqualTo(TextNode.valueOf(""));
        assertThat(sanitize("", schema("{\"type\":[\"integer\",\"boolean\"]}"))).isEqualTo(NullNode.getInstance());
        assertThat(sanitize("5", schema("{\"type\":[\"string\",\"integer\"]}"))).isEqualTo(TextNode.valueOf("5"));
        assertThat(sanitize("5", schema("{\"type\":[\"integer\",\"string\"]}"))).isEqualTo(IntNode.valueOf(5));
        assertThat(sanitize(NullNode.getInstance(), schema("{\"type\":[\"string\",\"null\"]}")))
                .isEqualTo(NullNode.getInstance());
        assertThat(sanitize(json("[\"x\"]"), schema("{}"))).isEqualTo(json("[\"x\"]"));
    }

    @Test
    void formats() {
        assertThat(sanitize(" a <b>x</b>", format("text-field"))).isEqualTo(TextNode.valueOf("a x"));
        assertThat(sanitize(" a <b>x</b>", schema("{\"format\":\"text-field\"}")))
                .isEqualTo(TextNode.valueOf("a x"));
        assertThat(sanitize(" a@b.co ", format("email"))).isEqualTo(TextNode.valueOf("a@b.co"));
        assertThat(sanitize(" 1.2.3.4 ", format("ip"))).isEqualTo(TextNode.valueOf("1.2.3.4"));
        assertThat(sanitize(" #123 ", format("hex-color"))).isEqualTo(TextNode.valueOf(""));
        assertThat(sanitize("#123\n", format("hex-color"))).isEqualTo(TextNode.valueOf("#123\n"));
        assertThat(sanitize("x", format("unknown"))).isEqualTo(TextNode.valueOf("x"));
        assertThat(sanitize(json("5"), schema("{\"type\":\"integer\",\"format\":\"text-field\"}")))
                .isEqualTo(IntNode.valueOf(5));
        assertThat(sanitize("a\tb%41\tc", format("textarea-field"))).isEqualTo(TextNode.valueOf("a\tb\tc"));
        assertThat(sanitize("a <b>x</b> <\n y", format("textarea-field"))).isEqualTo(TextNode.valueOf("a x &lt;\n y"));
    }

    @Test
    void formatsSanitizeTheRawValue() {
        for (String name :
                List.of("email", "date-time", "ip", "uuid", "text-field", "textarea-field", "hex-color", "uri")) {
            // WordPress gives "" for an array; with uri and hex-color it fails with a 500 instead
            assertThat(sanitize(json("[\"a\"]"), format(name))).as(name).isEqualTo(TextNode.valueOf(""));
            assertThat(sanitize(NullNode.getInstance(), schema("{\"format\":\"" + name + "\"}")))
                    .as(name)
                    .isEqualTo(TextNode.valueOf(""));
        }
        assertThat(sanitize(json("[\"a\"]"), schema("{\"format\":\"email\"}"))).isEqualTo(TextNode.valueOf(""));
        assertThat(sanitize(NullNode.getInstance(), schema("{\"format\":\"unknown\"}")))
                .isEqualTo(NullNode.getInstance());
        assertThat(sanitize(json("5"), schema("{\"format\":\"email\"}"))).isEqualTo(TextNode.valueOf("5"));
        assertThat(sanitize(json("true"), format("email"))).isEqualTo(TextNode.valueOf("1"));
        assertThat(sanitize(json("[\"a\"]"), format("unknown"))).isEqualTo(TextNode.valueOf("Array"));
    }

    @Test
    void duplicatesLeftBySanitizingAreReported() {
        ObjectNode ids = schema("{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"uniqueItems\":true}");
        JsonNode sanitized = sanitize(json("[\"1\",1]"), ids);
        assertThat(sanitized.toString()).isEqualTo("[1,1]");
        assertThat(SchemaSanitizer.duplicates(sanitized, ids, "p")
                        .orElseThrow()
                        .toJson()
                        .toString())
                .isEqualTo("{\"code\":\"rest_duplicate_items\",\"message\":\"p has duplicate items.\",\"data\":null}");
        assertThat(SchemaSanitizer.duplicates(sanitize(json("[\"1\",2]"), ids), ids, "p"))
                .isEmpty();
        ObjectNode multi =
                schema("{\"type\":[\"array\",\"string\"],\"items\":{\"type\":\"integer\"}," + "\"uniqueItems\":true}");
        assertThat(SchemaSanitizer.duplicates(sanitize(json("[\"1\",1]"), multi), multi, "p"))
                .isPresent();
        // a nested list is not reported: WordPress leaves its failure inside the sanitized value
        ObjectNode nested = schema("{\"type\":\"array\",\"items\":" + ids + "}");
        assertThat(SchemaSanitizer.duplicates(sanitize(json("[[\"1\",1]]"), nested), nested, "p"))
                .isEmpty();
    }

    @Test
    void textFieldsAsWordPressSanitizesThem() {
        Map<String, String[]> cases = Map.ofEntries(
                Map.entry("  a \t\n b  ", new String[] {"a b", "a \t\n b"}),
                Map.entry("a  b", new String[] {"a b", "a  b"}),
                Map.entry("a\n\nb", new String[] {"a b", "a\n\nb"}),
                Map.entry(" \n a%41b %41 c \n", new String[] {"ab c", "ab c"}),
                Map.entry("a <b>x</b> <\n y", new String[] {"a x &lt; y", "a x &lt;\n y"}),
                Map.entry("%%4141", new String[] {"", ""}),
                Map.entry("a%4", new String[] {"a%4", "a%4"}),
                Map.entry("<script>x</script>y", new String[] {"y", "y"}),
                Map.entry("a\r\nb", new String[] {"a b", "a\r\nb"}),
                Map.entry("\u0000a\u0000", new String[] {"a", "a"}),
                Map.entry("a %20 b", new String[] {"a b", "a b"}),
                Map.entry("a\tb%41\tc", new String[] {"a b c", "a\tb\tc"}),
                Map.entry("a\ud800b", new String[] {"", ""}));
        cases.forEach((input, expected) -> {
            assertThat(TextFields.textField(input)).as(input).isEqualTo(expected[0]);
            assertThat(TextFields.textareaField(input)).as(input).isEqualTo(expected[1]);
        });
    }

    @Test
    void urisAsWordPressSanitizesThem() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("not a uri", "http://not%20a%20uri"),
                Map.entry("http://x.test/a b", "http://x.test/a%20b"),
                Map.entry("ftp://x.test", "ftp://x.test"),
                Map.entry("javascript:alert(1)", ""),
                Map.entry("example.com", "http://example.com"),
                Map.entry("/path", "/path"),
                Map.entry("#frag", "#frag"),
                Map.entry("?q=1", "?q=1"),
                Map.entry("index.php", "index.php"),
                Map.entry("a.php?x", "a.php?x"),
                Map.entry("  http://x", "http://x"),
                Map.entry("http://x/<b>\"'", "http://x/b'"),
                Map.entry("http://[::1]:80/", "http://[::1]:80/"),
                Map.entry("http://x/%0d%0a", "http://x/"),
                Map.entry("HTTP://X", "http://X"),
                Map.entry("mailto:a@b.co", "mailto:a@b.co"),
                Map.entry("x:y", ""),
                Map.entry("//x.test", "//x.test"),
                Map.entry("http://x.test/\u00e9", "http://x.test/\u00e9"),
                Map.entry("", ""),
                Map.entry("http://x/;//y", "http://x/://y"),
                Map.entry("\u0000http://x", "http://x"),
                Map.entry("http://x/\\0", "http://x/0"),
                Map.entry("data:text/html,hi", ""),
                Map.entry("feed:http://x", "feed:http://x"),
                Map.entry("a@b.co", "http://a@b.co"),
                Map.entry("localhost:8080", ""),
                Map.entry("http://x/[a]", "http://x/%5Ba%5D"),
                Map.entry("http://[::1]/[a]?b[]=1", "http://[::1]/%5Ba%5D?b%5B%5D=1"),
                Map.entry("//x/[a]", "//x/%5Ba%5D"),
                Map.entry("/a[b]", "/a%5Bb%5D"),
                Map.entry("[a]", "http://[a]"),
                Map.entry("http://u:p@h:8/[x]", "http://u:p@h:8/%5Bx%5D"),
                Map.entry("mailto:a@b%0d%0a.co", "mailto:a@b%0d%0a.co"),
                Map.entry("a%0%0dd", "http://a"),
                Map.entry("http://x/%0%0D%0aDa", "http://x/a"),
                Map.entry("http:x", "http:x"),
                Map.entry(" http://x y ", "http://x%20y%20"),
                Map.entry("http://a\tb", "http://ab"),
                Map.entry("feed:javascript:x", ""),
                Map.entry("tel:+1", "tel:+1"));
        cases.forEach(
                (input, expected) -> assertThat(TextFields.url(input)).as(input).isEqualTo(expected));
        assertThat(sanitize("not a uri", format("uri"))).isEqualTo(TextNode.valueOf("http://not%20a%20uri"));
    }

    @Test
    void repeatedRemovalIsLinear() {
        int n = 300_000;
        String nested = "%".repeat(n) + "41".repeat(n);
        long[] work = new long[1];
        assertThat(TextFields.removeOctets(nested, work)).isEmpty();
        assertThat(work[0]).isLessThanOrEqualTo(nested.length());
        String breaks = "http://x/" + "%0".repeat(n) + "d".repeat(n);
        long[] urlWork = new long[1];
        assertThat(TextFields.removeLineBreakEscapes(breaks, urlWork)).isEqualTo("http://x/");
        assertThat(urlWork[0]).isLessThanOrEqualTo(breaks.length());
        long started = System.nanoTime();
        assertThat(TextFields.textField(nested)).isEmpty();
        assertThat(TextFields.url(breaks)).isEqualTo("http://x/");
        assertThat(TextFields.textField("<" + "a<".repeat(n))).isNotNull();
        assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
    }

    static JsonNode sanitize(String value, ObjectNode schema) {
        return sanitize(TextNode.valueOf(value), schema);
    }

    static JsonNode sanitize(JsonNode value, ObjectNode schema) {
        return SchemaSanitizer.sanitize(value, schema, "p");
    }

    static ObjectNode format(String format) {
        return string().put("format", format);
    }

    static ObjectNode objectWithout(String... properties) {
        ObjectNode schema = object();
        ObjectNode props = schema.putObject("properties");
        for (String name : properties) {
            props.set(name, integer());
        }
        schema.put("additionalProperties", false);
        return schema;
    }
}
