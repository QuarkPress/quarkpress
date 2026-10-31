package org.quarkpress.restwp.runtime;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.PhpObjectNode;

/**
 * WordPress's {@code _fields}: the server-level reduction, the list parsing, {@code rest_is_field_included} and the
 * controllers' field selection. Every expected value is WordPress 7.1.3's answer: rest_filter_response_fields,
 * wp_parse_list, rest_is_field_included and get_fields_for_response run by PHP 8.4.
 */
class FieldsFilterTest {

    final JsonNode item = json("{\"id\":1,\"title\":{\"raw\":\"a\",\"rendered\":\"b\"},\"x\":2}");

    @Test
    void parentBeforeChildStopsAtTheChild() {
        assertThat(reduce(item, "title,title.raw,id").toString())
                .isEqualTo("{\"title\":{\"raw\":\"a\",\"rendered\":\"b\"}}");
        assertThat(reduce(item, "title.raw,title,id").toString())
                .isEqualTo("{\"id\":1,\"title\":{\"raw\":\"a\",\"rendered\":\"b\"}}");
        // the stop is only for a parent requested whole before; a deeper parent set later does not stop
        assertThat(reduce(item, "a.b.c,a.b,id").toString()).isEqualTo("{\"id\":1}");
    }

    @Test
    void childrenReduceObjectsAndKeepScalars() {
        assertThat(reduce(item, "title.raw").toString()).isEqualTo("{\"title\":{\"raw\":\"a\"}}");
        assertThat(reduce(item, "x.y").toString()).isEqualTo("{\"x\":2}");
        assertThat(reduce(item, "title..raw").toString()).isEqualTo("{\"title\":[]}");
        assertThat(reduce(item, ".").toString()).isEqualTo("[]");
        assertThat(reduce(item, "id.").toString()).isEqualTo("{\"id\":1}");
    }

    @Test
    void aKeyedCollectionIsReducedByItsKeys() {
        JsonNode types = json("{\"post\":{\"name\":\"Posts\"},\"page\":{\"name\":\"Pages\"}}");
        assertThat(reduce(types, "name").toString()).isEqualTo("[]");
        assertThat(reduce(types, "post").toString()).isEqualTo("{\"post\":{\"name\":\"Posts\"}}");
    }

    @Test
    void listsAreReducedPerItemAndEmptyMeansAll() {
        assertThat(reduce(json("[{\"id\":1,\"x\":2},{\"id\":3}]"), "id").toString())
                .isEqualTo("[{\"id\":1},{\"id\":3}]");
        assertThat(reduce(item, " , ")).isEqualTo(item);
        assertThat(reduce(json("[{\"x\":1},{\"x\":2}]"), "id").toString()).isEqualTo("[[],[]]");
        // a body keyed by integers is a PHP list: each value reduced, the keys renumbered
        assertThat(reduce(json("{\"3\":{\"id\":1,\"x\":1},\"7\":{\"id\":2}}"), "id")
                        .toString())
                .isEqualTo("[{\"id\":1},{\"id\":2}]");
        assertThat(reduce(json("{}"), "id").toString()).isEqualTo("[]");
    }

    @Test
    void nestedListsAreReducedByTheirIndexes() {
        JsonNode lists = json("{\"tags\":[5,6,7],\"objs\":[{\"a\":1,\"b\":2},{\"a\":3}]}");
        assertThat(reduce(lists, "tags.0").toString()).isEqualTo("{\"tags\":[5]}");
        assertThat(reduce(lists, "tags.0,tags.1").toString()).isEqualTo("{\"tags\":[5,6]}");
        // PHP keeps the index, and an array whose keys are not 0, 1, 2 and so on encodes as an object
        assertThat(reduce(lists, "tags.1").toString()).isEqualTo("{\"tags\":{\"1\":6}}");
        assertThat(reduce(lists, "tags.x").toString()).isEqualTo("{\"tags\":[]}");
        assertThat(reduce(lists, "objs.a").toString()).isEqualTo("{\"objs\":[]}");
        assertThat(reduce(lists, "objs.1.a").toString()).isEqualTo("{\"objs\":{\"1\":{\"a\":3}}}");
        assertThat(reduce(lists, "objs.0.a").toString()).isEqualTo("{\"objs\":[{\"a\":1}]}");
    }

    @Test
    void phpObjectsAreKeptWhole() {
        // a post type's labels are a PHP object in WordPress: labels.name keeps every label
        ObjectNode post = RestJson.NODES.objectNode().put("name", "Posts");
        post.set(
                "labels",
                PhpObjectNode.of(
                        RestJson.NODES.objectNode().put("name", "Posts").put("singular_name", "Post")));
        post.put("slug", "post");
        String labels = "{\"labels\":{\"name\":\"Posts\",\"singular_name\":\"Post\"}}";
        assertThat(reduce(post, "labels.name").toString()).isEqualTo(labels);
        assertThat(reduce(post, "labels").toString()).isEqualTo(labels);
        assertThat(reduce(RestJson.NODES.arrayNode().add(post), "labels.name,slug")
                        .toString())
                .isEqualTo("[{\"labels\":{\"name\":\"Posts\",\"singular_name\":\"Post\"},\"slug\":\"post\"}]");
        ObjectNode empty = RestJson.NODES.objectNode();
        empty.set("m", new PhpObjectNode());
        assertThat(reduce(empty, "m.x").toString()).isEqualTo("{\"m\":{}}");
        ObjectNode inList = RestJson.NODES.objectNode();
        inList.putArray("x")
                .add(PhpObjectNode.of(RestJson.NODES.objectNode().put("a", 1).put("b", 2)));
        assertThat(reduce(inList, "x.0.a").toString()).isEqualTo("{\"x\":[{\"a\":1,\"b\":2}]}");
        // as a whole body WordPress fails (a TypeError); it is kept as it is, and is never taken for a list
        PhpObjectNode body = PhpObjectNode.of(RestJson.NODES.objectNode().put("a", 1));
        assertThat(reduce(body, "x")).isSameAs(body);
        PhpObjectNode numbered = PhpObjectNode.of((ObjectNode) json("{\"0\":{\"a\":1}}"));
        assertThat(reduce(numbered, "a")).isSameAs(numbered);
    }

    @Test
    void theInputIsNotChanged() {
        String before = item.toString();
        reduce(item, "title.raw");
        assertThat(item.toString()).isEqualTo(before);
    }

    @Test
    void aHugeFieldListIsFilteredLinearly() {
        String fields = IntStream.range(0, 10_000).mapToObj(i -> "f" + i).collect(joining(",")) + ",id";
        assertThat(reduce(item, fields).toString()).isEqualTo("{\"id\":1}");
        for (int n : new int[] {1_000, 10_000, 100_000}) {
            String many = IntStream.range(0, n).mapToObj(i -> "f" + i + ".g").collect(joining(","));
            long[] work = new long[1];
            List<String> parsed = FieldsFilter.parse(TextNode.valueOf(many), work);
            FieldsFilter.reduce(item, parsed, work);
            assertThat(work[0]).as("work for %d fields", n).isLessThanOrEqualTo(3L * many.length() + 20);
        }
    }

    @Test
    void deepPathsAndDeepDataNeitherOverflowNorTakeQuadraticWork() {
        int depth = 50_000;
        ObjectNode root = RestJson.NODES.objectNode();
        ObjectNode node = root;
        for (int i = 0; i < depth; i++) {
            node.put("x", i);
            node = node.putObject("a");
        }
        String path = IntStream.range(0, depth).mapToObj(i -> "a").collect(joining("."));
        long[] work = new long[1];
        JsonNode reduced = FieldsFilter.reduce(root, FieldsFilter.parse(TextNode.valueOf(path), work), work);
        assertThat(work[0]).isLessThanOrEqualTo(4L * path.length() + 4L * depth);
        JsonNode walk = reduced;
        for (int i = 0; i < depth - 1; i++) {
            assertThat(walk.size()).isEqualTo(1);
            walk = walk.get("a");
        }
        assertThat(walk.get("a")).isEqualTo(RestJson.NODES.objectNode());
    }

    @Test
    void parsingFollowsWordPresssListAndTrim() {
        assertThat(parse(TextNode.valueOf(" id ,\ttitle\nx\u000By\fz,,")))
                .containsExactly("id", "title", "x", "y", "z");
        // NUL is not a separator but trim removes it; a piece trimmed to nothing still counts
        assertThat(parse(TextNode.valueOf("\u0000,id\u0000"))).containsExactly("", "id");
        // only ASCII whitespace separates
        assertThat(parse(TextNode.valueOf("id\u00A0x\u0085y"))).containsExactly("id\u00A0x\u0085y");
        assertThat(parse(TextNode.valueOf(""))).isEmpty();
        assertThat(parse(json("5"))).containsExactly("5");
        assertThat(parse(BooleanNode.TRUE)).containsExactly("1");
        assertThat(parse(BooleanNode.FALSE)).isEmpty();
        assertThat(parse(json("1e20"))).containsExactly("1.0E+20");
        // an array is not split: its scalars are trimmed, its arrays and nulls dropped
        assertThat(parse(json("[\"id \",\"x,title\",true,1.5,null,[\"a\"],false,\"\\fz\"]")))
                .containsExactly("id", "x,title", "1", "1.5", "", "\fz");
        assertThat(parse(json("{\"a\":\"id\",\"b\":{}}"))).containsExactly("id");
        assertThat(reduce(item, json("[\"id \",\"x,title\",true,5,null,[\"a\"]]"))
                        .toString())
                .isEqualTo("{\"id\":1}");
    }

    @Test
    void includesIsSymmetricForParentsAndChildren() {
        assertThat(Fields.includes(List.of("title"), "title.raw")).isTrue();
        assertThat(Fields.includes(List.of("title.raw"), "title")).isTrue();
        assertThat(Fields.includes(List.of("_embedded"), "_links")).isFalse();
        assertThat(Fields.includes(List.of("title.raw"), "tit")).isFalse();
        assertThat(Fields.includes(List.of("titles"), "title")).isFalse();
        assertThat(Fields.includes(List.of(".a"), "")).isTrue();
        // as in WordPress, a plain empty list includes nothing
        assertThat(Fields.includes(List.of(), "anything")).isFalse();
        assertThat(Fields.includes(List.of(""), ".")).isTrue();
        assertThat(Fields.includes(List.of("a..b"), "a.")).isTrue();
        assertThat(Fields.includes(List.of("a.b"), "a.b.c.d")).isTrue();
        assertThat(Fields.includes(List.of("a.b.c"), "a.c")).isFalse();
    }

    @Test
    void controllersComputeTheIncludedSchemaFieldsForTheContext() {
        ObjectNode schema = (ObjectNode) json("{\"type\":\"object\",\"properties\":{"
                + "\"id\":{\"context\":[\"view\",\"edit\",\"embed\"]},\"title\":{\"context\":[\"view\",\"edit\"]},"
                + "\"raw\":{\"context\":[\"edit\"]},\"free\":{},\"empty\":{\"context\":[]}}}");
        assertThat(Fields.forResponse(List.of(), schema, "view").fields())
                .containsExactly("id", "title", "free", "empty", "_links");
        assertThat(Fields.forResponse(List.of(), schema, "edit").fields())
                .containsExactly("id", "title", "raw", "free", "empty", "_links");
        assertThat(Fields.forResponse(List.of(), schema, "embed", true).fields())
                .containsExactly("id", "free", "empty", "_links", "_embedded");
        // no context (or one PHP counts as false) excludes nothing
        assertThat(Fields.forResponse(List.of(), schema, null).fields())
                .containsExactly("id", "title", "raw", "free", "empty", "_links");
        assertThat(Fields.forResponse(List.of(), schema, "0").fields())
                .containsExactly("id", "title", "raw", "free", "empty", "_links");
        // the requested fields whose top level is a field, then id
        assertThat(Fields.forResponse(List.of("title.raw", "x", "raw", "_links.self", "free"), schema, "view")
                        .fields())
                .containsExactly("title.raw", "_links.self", "free", "id");
        assertThat(Fields.forResponse(List.of("_embedded"), schema, "view").fields())
                .containsExactly("id");
        assertThat(Fields.forResponse(List.of("_embedded"), schema, "view", true)
                        .fields())
                .containsExactly("_embedded", "id");
        assertThat(Fields.forResponse(List.of("title", "title"), schema, "view").fields())
                .containsExactly("title", "title", "id");
        ObjectNode noId = (ObjectNode) json("{\"properties\":{\"name\":{}}}");
        assertThat(Fields.forResponse(List.of("name.x", "zz"), noId, "view").fields())
                .containsExactly("name.x");
        // nothing requested is a field: nothing is selected, links included (WordPress returns an empty list)
        Fields.Selection none = Fields.forResponse(List.of("zz", "_embedded"), noId, "view");
        assertThat(none.kind()).isEqualTo(Fields.Selection.Kind.NOTHING);
        assertThat(none.includes("_links")).isFalse();
        assertThat(none.includes("name")).isFalse();
        // without _fields the schema's fields are selected, and only those (with their children)
        Fields.Selection all = Fields.forResponse(List.of(), noId, "view");
        assertThat(all.kind()).isEqualTo(Fields.Selection.Kind.THESE);
        assertThat(all.includes("name.raw")).isTrue();
        assertThat(all.includes("_links")).isTrue();
        assertThat(all.includes("other")).isFalse();
        assertThat(Fields.Selection.everything().includes("other")).isTrue();
    }

    @Test
    void theRequestsContextCountsByPhpTruth() {
        ObjectNode schema = (ObjectNode) json("{\"properties\":{"
                + "\"id\":{\"context\":[\"view\",\"edit\",\"embed\"]},\"title\":{\"context\":[\"view\",\"edit\"]},"
                + "\"raw\":{\"context\":[\"edit\"]},\"free\":{},\"empty\":{\"context\":[]}}}");
        for (String falsy : List.of("false", "0.0", "\"\"", "\"0\"", "[]")) {
            assertThat(Fields.forResponse(stub("{\"context\":" + falsy + "}"), schema)
                            .fields())
                    .as(falsy)
                    .containsExactly("id", "title", "raw", "free", "empty", "_links");
        }
        for (String truthy : List.of("[\"x\"]", "5", "true")) {
            assertThat(Fields.forResponse(stub("{\"context\":" + truthy + "}"), schema)
                            .fields())
                    .as(truthy)
                    .containsExactly("free", "empty", "_links");
        }
        assertThat(Fields.forResponse(
                                stub("{\"context\":\"edit\",\"_embed\":\"1\",\"_fields\":\"raw,_embedded\"}"), schema)
                        .fields())
                .containsExactly("raw", "_embedded", "id");
    }

    static StubRequest stub(String params) {
        return new StubRequest((ObjectNode) json(params), "http://localhost/wp-json/x", Set.of());
    }

    @Test
    void theRequestsFieldsComeFromAnySource() {
        ObjectNode params = RestJson.NODES.objectNode().put("_fields", "id, title");
        assertThat(Fields.of(new StubRequest(params, "http://localhost/wp-json/x", Set.of())))
                .containsExactly("id", "title");
        assertThat(Fields.of(new StubRequest(RestJson.NODES.objectNode(), "http://localhost/wp-json/x", Set.of())))
                .isEmpty();
        ArrayNode list =
                RestJson.NODES.objectNode().putArray("_fields").add("id").add("x,y");
        assertThat(Fields.of(new StubRequest(
                        (ObjectNode) RestJson.NODES.objectNode().set("_fields", list),
                        "http://localhost/wp-json/x",
                        Set.of())))
                .containsExactly("id", "x,y");
    }

    static JsonNode reduce(JsonNode data, String fields) {
        return reduce(data, TextNode.valueOf(fields));
    }

    static JsonNode reduce(JsonNode data, JsonNode fields) {
        return FieldsFilter.reduce(data, Fields.parse(fields));
    }

    static List<String> parse(JsonNode value) {
        return Fields.parse(value);
    }

    static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
