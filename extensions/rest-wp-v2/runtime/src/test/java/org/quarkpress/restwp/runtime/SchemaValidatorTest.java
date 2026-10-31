package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.RestError;

/** Expected codes and messages are WordPress 7.1.3's answers to the same value and schema (PHP 8.4). */
class SchemaValidatorTest {

    @Test
    void integers() {
        valid("5", integer());
        valid(" 5 ", integer());
        valid("5.0", integer());
        valid("1e1", integer());
        invalid("0x1A", integer(), "rest_invalid_type", "p is not of type integer.");
        invalid("", integer(), "rest_invalid_type", "p is not of type integer.");
        invalid("5.5", integer(), "rest_invalid_type", "p is not of type integer.");
    }

    @Test
    void numericStringsFollowPhpIsNumeric() {
        for (String ok :
                List.of("1e400", "-0", "+5", "5.", "\n5", "5\n", "\t5", "\u000b5", "\f5", "00", "-1e3", "1E1")) {
            valid(ok, integer());
        }
        for (String bad : List.of(".5", "1e", "  ", "1_000", "0b1", "\u00005", "5x", "--5", "5e+", ".")) {
            invalid(bad, integer(), "rest_invalid_type", "p is not of type integer.");
        }
        valid(".5", schema("{\"type\":\"number\"}"));
        invalid("x", schema("{\"type\":\"number\"}"), "rest_invalid_type", "p is not of type number.");
        valid(json("5.0"), integer());
        invalid(json("5.5"), integer(), "rest_invalid_type", "p is not of type integer.");
        invalid(json("true"), integer(), "rest_invalid_type", "p is not of type integer.");
    }

    @Test
    void booleansStringsArraysObjects() {
        for (String ok : List.of("true", "FALSE", "1", "0")) {
            valid(ok, bool());
        }
        for (String bad : List.of("", "yes", "on", "2")) {
            invalid(bad, bool(), "rest_invalid_type", "p is not of type boolean.");
        }
        valid(json("1"), bool());
        invalid(json("1.0"), bool(), "rest_invalid_type", "p is not of type boolean.");
        invalid(IntNode.valueOf(5), string(), "rest_invalid_type", "p is not of type string.");
        valid("1, 2,,3", array(integer()));
        valid("", array(integer()));
        invalid(json("{\"a\":\"1\"}"), array(integer()), "rest_invalid_type", "p is not of type array.");
        invalid("1,x", array(integer()), "rest_invalid_type", "p[1] is not of type integer.");
        valid("", object());
        invalid("x", object(), "rest_invalid_type", "p is not of type object.");
        invalid(
                "x",
                schema(
                        "{\"type\":[\"boolean\",\"array\"],\"items\":{\"type\":\"string\",\"enum\":[\"post\",\"page\"]}}"),
                "rest_not_in_enum",
                "p[0] is not one of post and page.");
        // an enum declared as an associative array (the users controller's post types) lists its values
        String keyed = "{\"type\":[\"boolean\",\"array\"],\"items\":{\"type\":\"string\","
                + "\"enum\":{\"post\":\"post\",\"page\":\"page\",\"k\":\"wp_block\"}}}";
        invalid("x", schema(keyed), "rest_not_in_enum", "p[0] is not one of post, page, and wp_block.");
        valid("post,wp_block", schema(keyed));
        invalid("k", schema(keyed), "rest_not_in_enum", "p[0] is not one of post, page, and wp_block.");
    }

    @Test
    void arraysAcceptPhpListKeysAndScalarsAndReindex() {
        valid(json("{\"1\":\"1\",\"-1\":\"2\"}"), array(integer()));
        for (String key : List.of("1.5", " 1", "01", "1e2", "0x1", "-0")) {
            invalid(json("{\"" + key + "\":\"x\"}"), array(null), "rest_invalid_type", "p is not of type array.");
        }
        // items are checked after re-indexing
        invalid(json("{\"3\":\"x\"}"), array(integer()), "rest_invalid_type", "p[0] is not of type integer.");
        valid(json("true"), array(integer()));
        invalid(json("5.5"), array(integer()), "rest_invalid_type", "p[0] is not of type integer.");
        invalid(NullNode.getInstance(), array(null), "rest_invalid_type", "p is not of type array.");
        // the splitter breaks on ASCII whitespace (vertical tab and form feed too) and commas only
        valid(" 1 2\t3\n4\r5\u000b6\f7", array(integer()));
        invalid("1\u00a02", array(integer()), "rest_invalid_type", "p[0] is not of type integer.");
        valid(json("[1]"), object());
        invalid(json("5"), object(), "rest_invalid_type", "p is not of type object.");
    }

    @Test
    void errorsCarryTheParamOnlyForTypeErrors() {
        RestError type =
                SchemaValidator.validate(text("1,x"), array(integer()), "p").orElseThrow();
        assertThat(type.toJson().toString())
                .isEqualTo("{\"code\":\"rest_invalid_type\",\"message\":\"p[1] is not of type integer.\","
                        + "\"data\":{\"param\":\"p[1]\"}}");
        RestError bounds =
                SchemaValidator.validate(text("0"), bounded(1, 100), "p").orElseThrow();
        assertThat(bounds.data()).isNull();
        RestError multi = SchemaValidator.validate(text("x"), schema("{\"type\":[\"boolean\",\"integer\"]}"), "p")
                .orElseThrow();
        assertThat(multi.toJson().toString())
                .isEqualTo("{\"code\":\"rest_invalid_type\",\"message\":\"p is not of type boolean,integer.\","
                        + "\"data\":{\"param\":\"p\"}}");
        invalid(text("x"), schema("{\"type\":\"null\"}"), "rest_invalid_type", "p is not of type null.");
        valid(NullNode.getInstance(), schema("{\"type\":[\"string\",\"null\"],\"format\":\"email\"}"));
        valid(json("5"), schema("{\"type\":[\"string\",\"integer\"],\"format\":\"email\"}"));
    }

    @Test
    void enumsBoundsAndFormats() {
        invalid("up", enumOf("asc", "desc"), "rest_not_in_enum", "p is not one of asc and desc.");
        invalid("EDIT", enumOf("view", "embed", "edit"), "rest_not_in_enum", "p is not one of view, embed, and edit.");
        invalid("y", enumOf("x"), "rest_not_in_enum", "p is not x.");
        invalid("0", bounded(1, 100), "rest_out_of_bounds", "p must be between 1 (inclusive) and 100 (inclusive)");
        invalid("1e400", bounded(1, 100), "rest_out_of_bounds", "p must be between 1 (inclusive) and 100 (inclusive)");
        invalid("0", minimum(1), "rest_out_of_bounds", "p must be greater than or equal to 1");
        invalid("1", exclusiveMinimum(1), "rest_out_of_bounds", "p must be greater than 1");
        invalid("11", maximum(10), "rest_out_of_bounds", "p must be less than or equal to 10");
        invalid("-1", minimum(0.5), "rest_out_of_bounds", "p must be greater than or equal to 0");
        invalid("2026-01-15", format("date-time"), "rest_invalid_date", "Invalid date.");
        valid("2026-01-15T10:00:00", format("date-time"));
        valid("2026-01-15 10:00:00.5+02:00", format("date-time"));
        valid("not a uri", format("uri"));
        invalid("nope", format("email"), "rest_invalid_email", "Invalid email address.");
        invalid("#12", format("hex-color"), "rest_invalid_hex_color", "Invalid hex color.");
    }

    @Test
    void enumsCompareTheSanitizedValue() {
        valid("5", schema("{\"type\":\"integer\",\"enum\":[5,6]}"));
        valid("5.0", schema("{\"type\":\"integer\",\"enum\":[5]}"));
        valid("5", schema("{\"type\":\"number\",\"enum\":[5]}"));
        invalid("5", schema("{\"type\":\"integer\",\"enum\":[\"5\"]}"), "rest_not_in_enum", "p is not 5.");
        valid("true", schema("{\"type\":\"boolean\",\"enum\":[true]}"));
        valid("a", schema("{\"type\":\"array\",\"enum\":[[\"a\"]]}"));
        valid(json("{\"a\":1,\"b\":2}"), schema("{\"type\":\"object\",\"enum\":[{\"b\":2,\"a\":1}]}"));
        invalid(
                "y",
                schema(
                        "{\"type\":\"string\",\"enum\":[true,false,null,0.30000000000000004,1e25,7,\"s\",[1],{\"a\":\"b\"}]}"),
                "rest_not_in_enum",
                "p is not one of 1, , null, 0.3, 1.0E+25, 7, s, [1], and {\"a\":\"b\"}.");
    }

    @Test
    void boundsVariants() {
        invalid(
                "10",
                schema("{\"type\":\"integer\",\"maximum\":10,\"exclusiveMaximum\":true}"),
                "rest_out_of_bounds",
                "p must be less than 10");
        invalid(
                "0",
                schema("{\"type\":\"integer\",\"minimum\":1,\"maximum\":100,\"exclusiveMinimum\":true,"
                        + "\"exclusiveMaximum\":true}"),
                "rest_out_of_bounds",
                "p must be between 1 (exclusive) and 100 (exclusive)");
        invalid(
                "100",
                schema("{\"type\":\"integer\",\"minimum\":1,\"maximum\":100,\"exclusiveMaximum\":true}"),
                "rest_out_of_bounds",
                "p must be between 1 (inclusive) and 100 (exclusive)");
        invalid(
                "0",
                schema("{\"type\":\"integer\",\"minimum\":1,\"maximum\":100,\"exclusiveMinimum\":true}"),
                "rest_out_of_bounds",
                "p must be between 1 (exclusive) and 100 (inclusive)");
        invalid(
                "5",
                schema("{\"type\":\"integer\",\"minimum\":5,\"exclusiveMinimum\":true,\"maximum\":4}"),
                "rest_out_of_bounds",
                "p must be between 5 (exclusive) and 4 (inclusive)");
        valid("5", schema("{\"type\":\"integer\",\"exclusiveMinimum\":true}"));
        // bounds print as PHP's integer conversion of the bound: truncated, and modulo 2^64 beyond the range
        invalid(
                "-1",
                schema("{\"type\":\"integer\",\"minimum\":-0.5}"),
                "rest_out_of_bounds",
                "p must be greater than or equal to 0");
        invalid(
                "-1",
                schema("{\"type\":\"integer\",\"minimum\":1e30}"),
                "rest_out_of_bounds",
                "p must be greater than or equal to 5076964154930102272");
    }

    @Test
    void integerBoundsAndMultiplesComeBeforeTheWholeNumberCheck() {
        invalid("0.5", minimum(1), "rest_out_of_bounds", "p must be greater than or equal to 1");
        invalid(
                "11.5",
                schema("{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}"),
                "rest_out_of_bounds",
                "p must be between 1 (inclusive) and 10 (inclusive)");
        invalid(
                "2.5",
                schema("{\"type\":\"integer\",\"multipleOf\":3}"),
                "rest_invalid_multiple",
                "p must be a multiple of 3.");
        invalid(
                "2.5",
                schema("{\"type\":\"integer\",\"multipleOf\":0.5}"),
                "rest_invalid_type",
                "p is not of type integer.");
        invalid("1.5", minimum(1), "rest_invalid_type", "p is not of type integer.");
        invalid("x", minimum(1), "rest_invalid_type", "p is not of type integer.");
    }

    @Test
    void remainingConstraintsInWordPressOrder() {
        invalid(
                "7",
                schema("{\"type\":\"integer\",\"multipleOf\":2,\"minimum\":10}"),
                "rest_invalid_multiple",
                "p must be a multiple of 2.");
        invalid(
                "7.25",
                schema("{\"type\":\"number\",\"multipleOf\":0.5}"),
                "rest_invalid_multiple",
                "p must be a multiple of 0.5.");
        invalid(
                "1",
                schema("{\"type\":\"number\",\"multipleOf\":0.3333333333333333}"),
                "rest_invalid_multiple",
                "p must be a multiple of 0.33333333333333.");
        invalid(
                "1e400",
                schema("{\"type\":\"number\",\"multipleOf\":2}"),
                "rest_invalid_multiple",
                "p must be a multiple of 2.");
        valid("10", schema("{\"type\":\"number\",\"multipleOf\":2.5}"));
        valid("-4", schema("{\"type\":\"integer\",\"multipleOf\":2}"));
        invalid(
                "0",
                schema("{\"type\":\"integer\",\"enum\":[5],\"minimum\":1}"),
                "rest_out_of_bounds",
                "p must be greater than or equal to 1");
        invalid(
                "ab",
                schema("{\"type\":\"string\",\"minLength\":3}"),
                "rest_too_short",
                "p must be at least 3 characters long.");
        invalid(
                "",
                schema("{\"type\":\"string\",\"minLength\":1}"),
                "rest_too_short",
                "p must be at least 1 character long.");
        invalid(
                "ab",
                schema("{\"type\":\"string\",\"enum\":[\"ab\"],\"maxLength\":1}"),
                "rest_too_long",
                "p must be at most 1 character long.");
        // characters, not UTF-16 units
        valid(new String(Character.toChars(0x1F600)), schema("{\"type\":\"string\",\"maxLength\":1}"));
        invalid(
                "#12",
                schema("{\"type\":\"string\",\"format\":\"hex-color\",\"enum\":[\"y\"]}"),
                "rest_not_in_enum",
                "p is not y.");
        invalid(
                "x",
                schema("{\"type\":\"string\",\"format\":\"email\",\"minLength\":5}"),
                "rest_too_short",
                "p must be at least 5 characters long.");
        valid("5", schema("{\"type\":\"integer\",\"format\":\"email\"}"));
        invalid(json("5"), schema("{\"format\":\"email\"}"), "rest_invalid_email", "Invalid email address.");
    }

    @Test
    void arrayCountsAndUniqueness() {
        invalid(
                "x,x",
                schema("{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":5}"),
                "rest_invalid_type",
                "p[0] is not of type integer.");
        invalid(
                "1,1",
                schema("{\"type\":\"array\",\"minItems\":5,\"uniqueItems\":true}"),
                "rest_too_few_items",
                "p must contain at least 5 items.");
        invalid(
                json("[1,2]"),
                schema("{\"type\":\"array\",\"maxItems\":1}"),
                "rest_too_many_items",
                "p must contain at most 1 item.");
        invalid(
                json("[]"),
                schema("{\"type\":\"array\",\"minItems\":1}"),
                "rest_too_few_items",
                "p must contain at least 1 item.");
        invalid(
                "1,1",
                schema("{\"type\":\"array\",\"uniqueItems\":true,\"enum\":[[\"2\"]]}"),
                "rest_duplicate_items",
                "p has duplicate items.");
        for (String duplicated : List.of(
                "[\"a\",\"a\"]",
                "[[1],[1]]",
                "[{\"a\":1,\"b\":2},{\"b\":2,\"a\":1}]",
                "[null,null]",
                "[1.5,1.5]",
                "[5,\"x\",5]",
                "[true,true]",
                "[\"\",\"\"]",
                "[{\"0\":1,\"1\":2},[1,2]]")) {
            invalid(json(duplicated), unique(), "rest_duplicate_items", "p has duplicate items.");
        }
        // PHP's strict comparison: types differ
        for (String distinct : List.of(
                "[1,1.0]",
                "[true,1]",
                "[\"1\",1]",
                "[\"1\",\"1.0\"]",
                "[0,false]",
                "[\"\",null]",
                "[[1],[\"1\"]]",
                "[{\"a\":1},{\"a\":\"1\"}]",
                "[[1,2],[2,1]]")) {
            valid(json(distinct), unique());
        }
    }

    @Test
    void objectConstraints() {
        invalid(
                json("{\"a\":\"x\"}"),
                schema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}," + "\"required\":[\"b\"]}"),
                "rest_property_required",
                "b is a required property of p.");
        invalid(
                json("{\"a\":1}"),
                schema("{\"type\":\"object\",\"required\":[\"c\",\"b\"]}"),
                "rest_property_required",
                "c is a required property of p.");
        // the list form replaces the properties' own flags
        valid(
                json("{\"d\":1}"),
                schema("{\"type\":\"object\",\"properties\":{\"b\":{\"required\":true}}," + "\"required\":[\"d\"]}"));
        invalid(
                json("{\"a\":\"x\"}"),
                schema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},"
                        + "\"b\":{\"required\":true}}}"),
                "rest_property_required",
                "b is a required property of p.");
        valid(json("{\"b\":null}"), schema("{\"type\":\"object\",\"required\":[\"b\"]}"));
        invalid(
                json("{\"b\":null}"),
                schema("{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"integer\"}}}"),
                "rest_invalid_type",
                "p[b] is not of type integer.");
        // properties are checked in the value's order
        invalid(
                json("{\"z\":1,\"a\":\"x\"}"),
                schema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":"
                        + "\"integer\"}},\"additionalProperties\":false}"),
                "rest_additional_properties_forbidden",
                "z is not a valid property of Object.");
        invalid(
                json("{\"a\":\"x\",\"z\":1}"),
                schema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":"
                        + "\"integer\"}},\"additionalProperties\":false}"),
                "rest_invalid_type",
                "p[a] is not of type integer.");
        invalid(
                json("{\"q\":\"x\"}"),
                schema("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"boolean\"}}"),
                "rest_invalid_type",
                "p[q] is not of type boolean.");
        invalid(
                json("{\"k\":[5]}"),
                schema("{\"type\":\"object\",\"properties\":{\"k\":{\"type\":\"array\","
                        + "\"items\":{\"type\":\"string\"}}}}"),
                "rest_invalid_type",
                "p[k][0] is not of type string.");
        invalid(
                json("{\"a\":1}"),
                schema("{\"type\":\"object\",\"minProperties\":2}"),
                "rest_too_few_properties",
                "p must contain at least 2 properties.");
        invalid(
                json("{\"a\":1,\"b\":2}"),
                schema("{\"type\":\"object\",\"maxProperties\":1}"),
                "rest_too_many_properties",
                "p must contain at most 1 property.");
    }

    @Test
    void dateTimes() {
        for (String ok : List.of(
                "2026-01-15t10:00:00",
                "2026-01-15T10:00:00Z",
                "2026-01-15T10:00:00+02",
                "2026-02-30T10:00:00",
                "2026-01-15T10:00:60",
                "2026-01-15T10:00:00.123456789",
                "2026-01-15T10:00:00-23:59",
                "2026-01-15T10:00:00+24:59",
                "0000-01-01T00:00:00",
                "2026-01-15T10:00:00\n",
                "2026-01-15T24:59:59",
                "2026-00-15T10:00:00",
                "2026-01-00T10:00:00",
                "2026-01-15T10:00:00.5Z",
                "2026-01-15T10:00:00+99",
                "2026-01-15T10:00:00-25",
                "2026-01-15T10:00:00Z\n")) {
            valid(ok, format("date-time"));
        }
        for (String bad : List.of(
                "2026-01-15T10:00:00z",
                "2026-01-15T10:00:00+0200",
                "2026-01-15T10:00",
                "2026-13-15T10:00:00",
                "2026-01-32T10:00:00",
                "2026-01-15T25:00:00",
                "2026-01-15T10:61:00",
                "2026-01-15T10:00:61",
                " 2026-01-15T10:00:00",
                "2026-01-15T10:00:00 ",
                "2026-01-15T10:00:00.",
                "26-01-15T10:00:00",
                "2026-1-15T10:00:00",
                "2026-01-15T10:00:00+2:00",
                "2026-01-15T10:00:00+25:00",
                "2026-01-15T10:00:00+23:60",
                "2026-01-15T10:00:00\n\n",
                "2026-01-15T10:00:00\r",
                "2026-01-15T10:00:00Z+02:00",
                "2026-01-15T10:00:00+02:0")) {
            invalid(bad, format("date-time"), "rest_invalid_date", "Invalid date.");
        }
    }

    @Test
    void emails() {
        for (String ok : List.of(
                "a@b.co",
                "ab@b.c",
                ".a@c.co",
                "a..b@c.co",
                "a@B.CO",
                "a@b.c-o",
                "!#$%&'*+/=?^_`{|}~@c.co",
                "a@b.c.d.e.f",
                "xx@x.x")) {
            valid(ok, format("email"));
        }
        for (String bad : List.of(
                "a@b.c",
                "@b.co",
                "a@@b.co",
                "a@b..co",
                "a@.b.co",
                "a@b.co.",
                "a@-b.co",
                "a@b-.co",
                "a@b_c.co",
                "a b@c.co",
                "a@b",
                "\u00e9@b.co",
                "a@\u00e9.co",
                "a@b.co ",
                "\ta@b.co",
                "a\"b@c.co",
                "a@1.2",
                "x@x.x")) {
            invalid(bad, format("email"), "rest_invalid_email", "Invalid email address.");
        }
    }

    @Test
    void ipsUuidsAndHexColors() {
        for (String ok : List.of(
                "1.2.3.4",
                "01.2.3.4",
                "001.2.3.4",
                "::1",
                "2001:db8::1",
                "::ffff:1.2.3.4",
                "1.2.3.4\n",
                "1:2:3:4:5:6:7:8",
                "::",
                "2001:DB8::1",
                "1:2:3:4:5:6:1.2.3.4",
                "1:2:3:4:5:6:7::",
                "1:2:3:4:5:6:7:8::",
                "1:02:3::",
                "1:2::3:4:5:6:7:8",
                "a:b:c:d:e:f:1.2.3.4")) {
            valid(ok, format("ip"));
        }
        for (String bad : List.of(
                "256.1.1.1",
                "1.2.3",
                " 1.2.3.4",
                "fe80::1%eth0",
                "1:2:3:4:5:6:7:8:9",
                "1.2.3.-1",
                "[::1]",
                "1::2::3",
                "1:00002:3::",
                "1::256.1.1.1",
                "1::01.1.1.1",
                "::1\n",
                "1:2:3:4:5:6:7:8:9::",
                "::1:2:3:4:5:6:7:8:9",
                "1:2:3:4:5:6:7::1.2.3.4")) {
            invalid(bad, format("ip"), "rest_invalid_ip", "p is not a valid IP address.");
        }
        valid("123e4567-e89b-12d3-a456-426614174000", format("uuid"));
        valid("123e4567-e89b-12d3-a456-426614174000\n", format("uuid"));
        for (String bad : List.of(
                "123E4567-E89B-12D3-A456-426614174000",
                "123e4567e89b12d3a456426614174000",
                "{123e4567-e89b-12d3-a456-426614174000}")) {
            invalid(bad, format("uuid"), "rest_invalid_uuid", "p is not a valid UUID.");
        }
        for (String ok : List.of("#123", "#abc", "#ABCDEF", "#123\n")) {
            valid(ok, format("hex-color"));
        }
        for (String bad : List.of("#abcdeg", "123", "#1234", " #123", "#")) {
            invalid(bad, format("hex-color"), "rest_invalid_hex_color", "Invalid hex color.");
        }
    }

    @Test
    void patternIsRejectedWhenTheSchemaIsRegistered() {
        for (String schema : List.of(
                "{\"type\":\"string\",\"pattern\":\"^a\"}",
                "{\"type\":\"array\",\"items\":{\"type\":\"string\",\"pattern\":\"^a\"}}",
                "{\"type\":\"object\",\"properties\":{\"k\":{\"type\":\"string\",\"pattern\":\"^a\"}}}",
                "{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\",\"pattern\":\"^a\"}}")) {
            assertThatThrownBy(() -> SchemaValidator.requireSupported(schema(schema)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("unsupported schema keyword: pattern");
        }
        assertThatCode(() -> SchemaValidator.requireSupported(schema("{\"type\":\"string\",\"format\":\"email\"}")))
                .doesNotThrowAnyException();
        // a property named like the keyword is not the keyword
        assertThatCode(() -> SchemaValidator.requireSupported(
                        schema("{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"}}}")))
                .doesNotThrowAnyException();
        for (String keyword : List.of("patternProperties", "anyOf", "oneOf")) {
            assertThatThrownBy(() -> SchemaValidator.requireSupported(schema("{\"" + keyword + "\":{}}")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("unsupported schema keyword: " + keyword);
        }
    }

    @Test
    void validatingLongInputIsLinear() {
        int n = 200_000;
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < n; i++) {
            list.append(i).append(',');
        }
        long started = System.nanoTime();
        valid(list.toString(), schema("{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"uniqueItems\":true}"));
        valid("a".repeat(1_000_000) + "@b.co", format("email"));
        valid("2026-01-15T10:00:00." + "1".repeat(1_000_000), format("date-time"));
        invalid(
                "9".repeat(1_000_000),
                bounded(1, 100),
                "rest_out_of_bounds",
                "p must be between 1 (inclusive) and 100 (inclusive)");
        invalid("1:".repeat(500_000), format("ip"), "rest_invalid_ip", "p is not a valid IP address.");
        assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
        long[] work = new long[1];
        assertThat(PhpValues.parseList(list.toString(), work)).hasSize(n);
        assertThat(work[0]).isLessThanOrEqualTo(list.length());
    }

    // helpers

    static void valid(String value, ObjectNode schema) {
        valid(text(value), schema);
    }

    static void valid(JsonNode value, ObjectNode schema) {
        assertThat(SchemaValidator.validate(value, schema, "p")).isEmpty();
    }

    static void invalid(String value, ObjectNode schema, String code, String message) {
        invalid(text(value), schema, code, message);
    }

    static void invalid(JsonNode value, ObjectNode schema, String code, String message) {
        Optional<RestError> error = SchemaValidator.validate(value, schema, "p");
        assertThat(error).as("%s against %s", value, schema).isPresent();
        assertThat(error.get().code()).as("%s against %s", value, schema).isEqualTo(code);
        assertThat(error.get().message()).as("%s against %s", value, schema).isEqualTo(message);
    }

    static TextNode text(String value) {
        return TextNode.valueOf(value);
    }

    static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ObjectNode schema(String text) {
        return (ObjectNode) json(text);
    }

    static ObjectNode integer() {
        return schema("{\"type\":\"integer\"}");
    }

    static ObjectNode bool() {
        return schema("{\"type\":\"boolean\"}");
    }

    static ObjectNode string() {
        return schema("{\"type\":\"string\"}");
    }

    static ObjectNode object() {
        return schema("{\"type\":\"object\"}");
    }

    static ObjectNode unique() {
        return schema("{\"type\":\"array\",\"uniqueItems\":true}");
    }

    static ObjectNode array(ObjectNode items) {
        ObjectNode schema = schema("{\"type\":\"array\"}");
        if (items != null) {
            schema.set("items", items);
        }
        return schema;
    }

    static ObjectNode enumOf(String... values) {
        ObjectNode schema = string();
        var array = schema.putArray("enum");
        for (String value : values) {
            array.add(value);
        }
        return schema;
    }

    static ObjectNode bounded(long minimum, long maximum) {
        ObjectNode schema = integer();
        schema.put("minimum", minimum);
        schema.put("maximum", maximum);
        return schema;
    }

    static ObjectNode minimum(long minimum) {
        return integer().put("minimum", minimum);
    }

    static ObjectNode minimum(double minimum) {
        return integer().put("minimum", minimum);
    }

    static ObjectNode exclusiveMinimum(long minimum) {
        return minimum(minimum).put("exclusiveMinimum", true);
    }

    static ObjectNode maximum(long maximum) {
        return integer().put("maximum", maximum);
    }

    static ObjectNode format(String format) {
        return string().put("format", format);
    }
}
