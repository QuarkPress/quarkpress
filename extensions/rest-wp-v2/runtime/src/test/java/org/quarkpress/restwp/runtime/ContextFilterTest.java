package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.PhpObjectNode;

/**
 * WordPress's filtering of a response by its context. Every expected value is WordPress 7.1.3's answer:
 * rest_filter_response_by_context run by PHP 8.4 on the same data and schema.
 */
class ContextFilterTest {

    static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"id\":{\"type\":\"integer\",\"context\":[\"view\",\"edit\"]},"
            + "\"raw\":{\"type\":\"string\",\"context\":[\"edit\"]},\"free\":{\"type\":\"string\"},"
            + "\"meta\":{\"type\":\"object\",\"context\":[\"view\",\"edit\"],\"properties\":{"
            + "\"a\":{\"context\":[\"edit\"]},\"b\":{\"context\":[\"view\"]}},"
            + "\"additionalProperties\":{\"context\":[\"edit\"]}},"
            + "\"tags\":{\"type\":\"array\",\"context\":[\"view\",\"edit\"],"
            + "\"items\":{\"type\":\"integer\",\"context\":[\"edit\"]}},"
            + "\"objs\":{\"type\":\"array\",\"context\":[\"view\"],\"items\":{\"type\":\"object\",\"properties\":{"
            + "\"p\":{\"context\":[\"edit\"]},\"q\":{\"context\":[\"view\"]}}}},"
            + "\"both\":{\"type\":[\"array\",\"object\"],\"context\":[\"view\"],\"items\":{\"context\":[\"edit\"]},"
            + "\"properties\":{\"k\":{\"context\":[\"edit\"]}}},"
            + "\"noctx\":{\"type\":\"object\",\"properties\":{\"z\":{\"context\":[\"edit\"]}}},"
            + "\"scal\":{\"type\":\"string\",\"context\":[\"view\"],\"properties\":{\"z\":{\"context\":[\"edit\"]}}}}}";

    static final String DATA =
            "{\"id\":1,\"raw\":\"r\",\"free\":\"f\",\"extra\":\"e\",\"meta\":{\"a\":1,\"b\":2,\"c\":3},"
                    + "\"tags\":[1,2],\"objs\":[{\"p\":1,\"q\":2},{\"q\":3}],\"both\":{\"k\":1,\"l\":2},"
                    + "\"noctx\":{\"z\":1,\"y\":2},\"scal\":{\"z\":1}}";

    @Test
    void propertiesWithoutAContextListAreKeptAndOthersNeedTheContext() {
        assertThat(apply(DATA, SCHEMA, "view"))
                .isEqualTo("{\"id\":1,\"free\":\"f\",\"extra\":\"e\",\"meta\":{\"b\":2},\"tags\":[],"
                        + "\"objs\":[{\"p\":1,\"q\":2},{\"q\":3}],\"both\":{\"l\":2},\"noctx\":{\"z\":1,\"y\":2},"
                        + "\"scal\":{\"z\":1}}");
        assertThat(apply(DATA, SCHEMA, "edit"))
                .isEqualTo("{\"id\":1,\"raw\":\"r\",\"free\":\"f\",\"extra\":\"e\",\"meta\":{\"a\":1,\"c\":3},"
                        + "\"tags\":[1,2],\"noctx\":{\"z\":1,\"y\":2}}");
        assertThat(apply(DATA, SCHEMA, "embed"))
                .isEqualTo("{\"free\":\"f\",\"extra\":\"e\",\"noctx\":{\"z\":1,\"y\":2}}");
    }

    @Test
    void nestedObjectsUsePropertiesThenAdditionalProperties() {
        // a declared property wins over additionalProperties; an undeclared key falls back to it
        assertThat(apply(
                        "{\"m\":{\"a\":1,\"c\":3}}",
                        "{\"properties\":{\"m\":{\"type\":\"object\","
                                + "\"context\":[\"view\"],\"properties\":{\"a\":{\"context\":[\"view\"]}},"
                                + "\"additionalProperties\":{\"context\":[\"edit\"]}}}}",
                        "view"))
                .isEqualTo("{\"m\":{\"a\":1}}");
        // additionalProperties alone does not make an object type
        assertThat(apply("{\"a\":{\"b\":1}}", "{\"additionalProperties\":{\"context\":[\"edit\"]}}", "view"))
                .isEqualTo("{\"a\":{\"b\":1}}");
        assertThat(apply(
                        "{\"a\":{\"b\":1}}",
                        "{\"type\":\"object\",\"additionalProperties\":{\"context\":[\"edit\"]}}",
                        "view"))
                .isEqualTo("[]");
    }

    @Test
    void anArrayWhoseItemsExcludeTheContextBecomesEmpty() {
        assertThat(apply("{\"both\":[1,2]}", SCHEMA, "view")).isEqualTo("{\"both\":[]}");
        assertThat(apply(
                        "[{\"a\":1}]",
                        "{\"type\":\"array\",\"items\":{\"type\":\"object\","
                                + "\"properties\":{\"a\":{\"context\":[\"edit\"]}}}}",
                        "view"))
                .isEqualTo("[{\"a\":1}]");
        // an object emptied by the filter is an empty PHP array
        assertThat(apply("{\"a\":{\"b\":1}}", "{\"properties\":{\"a\":{\"context\":[\"edit\"]}}}", "view"))
                .isEqualTo("[]");
        assertThat(apply("\"str\"", SCHEMA, "view")).isEqualTo("\"str\"");
    }

    @Test
    void whatIsLeftPrintsAsPhpPrintsTheArray() {
        String numbered = "{\"properties\":{\"m\":{\"type\":\"object\",\"context\":[\"view\"],\"properties\":{"
                + "\"a\":{\"context\":[\"edit\"]},\"x\":{\"context\":[\"edit\"]}}}}}";
        // keys 0, 1 left in order: a list
        assertThat(apply("{\"m\":{\"x\":1,\"0\":2,\"1\":3}}", numbered, "view")).isEqualTo("{\"m\":[2,3]}");
        assertThat(apply("{\"m\":{\"a\":1}}", numbered, "view")).isEqualTo("{\"m\":[]}");
        // a list under an object schema keeps the indexes of what is left
        String first = "{\"properties\":{\"l\":{\"type\":\"object\",\"context\":[\"view\"],"
                + "\"properties\":{\"0\":{\"context\":[\"edit\"]}}}}}";
        assertThat(apply("{\"l\":[\"a\",\"b\",\"c\"]}", first, "view")).isEqualTo("{\"l\":{\"1\":\"b\",\"2\":\"c\"}}");
        String last = "{\"properties\":{\"l\":{\"type\":\"object\",\"context\":[\"view\"],"
                + "\"properties\":{\"2\":{\"context\":[\"edit\"]}}}}}";
        assertThat(apply("{\"l\":[\"a\",\"b\",\"c\"]}", last, "view")).isEqualTo("{\"l\":[\"a\",\"b\"]}");
        // an object keyed 0, 1 under a type naming array and object is an array: its items schema applies
        assertThat(apply("{\"both\":{\"0\":1,\"1\":2}}", SCHEMA, "view")).isEqualTo("{\"both\":[]}");
    }

    @Test
    void phpObjectsStayObjects() {
        String numbered = "{\"type\":\"object\",\"properties\":{\"m\":{\"type\":\"object\",\"context\":[\"view\"],"
                + "\"properties\":{\"a\":{\"context\":[\"edit\"]},\"0\":{\"context\":[\"view\"]},"
                + "\"1\":{\"context\":[\"view\"]}}}}}";
        assertThat(applyObject("m", "{\"a\":1,\"0\":2,\"1\":3}", numbered)).isEqualTo("{\"m\":{\"0\":2,\"1\":3}}");
        assertThat(applyObject("m", "{\"a\":1}", numbered)).isEqualTo("{\"m\":{}}");
        String both = "{\"properties\":{\"both\":{\"type\":[\"array\",\"object\"],\"context\":[\"view\"],"
                + "\"items\":{\"context\":[\"edit\"]},\"properties\":{\"0\":{\"context\":[\"edit\"]}}}}}";
        assertThat(applyObject("both", "{\"0\":1,\"1\":2}", both)).isEqualTo("{\"both\":{\"1\":2}}");
        String array = "{\"properties\":{\"o\":{\"type\":\"array\",\"context\":[\"view\"],"
                + "\"items\":{\"context\":[\"edit\"]}}}}";
        assertThat(applyObject("o", "{\"a\":1}", array)).isEqualTo("{\"o\":[]}");
        JsonNode kept = ContextFilter.apply(wrap("m", "{\"0\":2,\"1\":3}"), (ObjectNode) json(numbered), "view")
                .get("m");
        assertThat(kept).isInstanceOf(PhpObjectNode.class);
        // a PHP object is removed like any value
        assertThat(applyObject(
                        "labels",
                        "{\"name\":\"P\"}",
                        "{\"properties\":{\"labels\":{\"type\":\"object\"," + "\"context\":[\"edit\"]}}}"))
                .isEqualTo("[]");
    }

    static ObjectNode wrap(String key, String object) {
        ObjectNode data = RestJson.NODES.objectNode();
        data.set(key, PhpObjectNode.of((ObjectNode) json(object)));
        return data;
    }

    static String applyObject(String key, String object, String schema) {
        return ContextFilter.apply(wrap(key, object), (ObjectNode) json(schema), "view")
                .toString();
    }

    @Test
    void theInputIsNotChanged() {
        JsonNode data = json(DATA);
        ContextFilter.apply(data, (ObjectNode) json(SCHEMA), "embed");
        assertThat(data).isEqualTo(json(DATA));
    }

    @Test
    void theWalkIsLinearInTheData() {
        for (int n : new int[] {1_000, 10_000, 100_000}) {
            ObjectNode data = RestJson.NODES.objectNode();
            ArrayNode list = data.putArray("list");
            ObjectNode map = data.putObject("map");
            for (int i = 0; i < n; i++) {
                list.addObject().put("p", i).put("q", i);
                map.putObject("k" + i).put("p", i);
            }
            ObjectNode schema = (ObjectNode) json("{\"type\":\"object\",\"properties\":{"
                    + "\"list\":{\"type\":\"array\",\"context\":[\"view\"],\"items\":{\"type\":\"object\","
                    + "\"context\":[\"view\"],\"properties\":{\"p\":{\"context\":[\"edit\"]}}}},"
                    + "\"map\":{\"type\":\"object\",\"context\":[\"view\"],\"additionalProperties\":{"
                    + "\"type\":\"object\",\"context\":[\"view\"],\"properties\":{\"p\":{\"context\":[\"view\"]}}}}}}");
            long[] work = new long[1];
            JsonNode filtered = ContextFilter.apply(data, schema, "view", work);
            assertThat(filtered.at("/list/" + (n - 1)).toString()).isEqualTo("{\"q\":" + (n - 1) + "}");
            assertThat(work[0]).as("work for %d items", n).isLessThanOrEqualTo(6L * n + 10);
        }
    }

    static String apply(String data, String schema, String context) {
        return ContextFilter.apply(json(data), (ObjectNode) json(schema), context)
                .toString();
    }

    static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
