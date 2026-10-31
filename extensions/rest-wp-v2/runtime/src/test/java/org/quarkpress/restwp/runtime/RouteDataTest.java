package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestUrls;

/** Route data as WordPress's indexes and OPTIONS show it; expected texts from WordPress 7.1.3's saved OPTIONS bodies. */
class RouteDataTest {

    static final RestUrls URLS = new RestUrls() {
        @Override
        public String root() {
            return "http://localhost/wp-json/";
        }

        @Override
        public String route(String path) {
            return root() + (path.startsWith("/") ? path.substring(1) : path);
        }

        @Override
        public String route(String path, Map<String, String> query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String home() {
            return "http://localhost/";
        }
    };

    @Test
    void argsShowOnlyWordPresssKeywordsInDeclaredOrderThenRequired() throws Exception {
        ObjectNode schema = (ObjectNode) RestJson.READER.readTree("{\"type\":\"string\",\"readonly\":true,"
                + "\"context\":[\"view\"],\"description\":\"D\",\"arg_options\":{\"x\":1},\"minLength\":1,"
                + "\"enum\":[\"a\",\"b\"],\"validate_callback\":\"x\",\"default\":\"a\",\"uniqueItems\":true}");
        Arg arg = new Arg("a", schema, true, Optional.empty(), Arg.Phase.VALIDATE, Optional.empty());
        assertThat(RouteData.describe(arg).toString())
                .isEqualTo("{\"type\":\"string\",\"description\":\"D\",\"minLength\":1,\"enum\":[\"a\",\"b\"],"
                        + "\"default\":\"a\",\"uniqueItems\":true,\"required\":true}");
        assertThat(RouteData.describe(Arg.of("b", null).build()).toString()).isEqualTo("{\"required\":false}");
    }

    @Test
    void schemaOnlyInHelpContextAndSelfLinkOnlyWithoutParameters() {
        RouteTable table = new RouteTable();
        ObjectNode item = RestJson.NODES.objectNode().put("title", "thing").put("type", "object");
        ObjectNode id = item.putObject("properties").putObject("id");
        id.put("type", "integer");
        id.putArray("context").add("view").add("edit");
        id.putObject("arg_options").put("sanitize_callback", "absint");
        table.registry()
                .route("app/v1", "/things")
                .schema(() -> item)
                .endpoint(Endpoint.of("GET")
                        .args(Paging.collectionArgs(item, false))
                        .allowBatch(true)
                        .handler(request -> RestResponse.ok(RestJson.NODES.arrayNode()))
                        .build())
                .endpoint(Endpoint.of("POST", "PUT")
                        .handler(request -> RestResponse.ok(RestJson.NODES.objectNode()))
                        .build())
                .endpoint(Endpoint.of("POST")
                        .showInIndex(false)
                        .handler(request -> RestResponse.ok(RestJson.NODES.objectNode()))
                        .build())
                .endpoint(Endpoint.of("PUT")
                        .handler(request -> RestResponse.ok(RestJson.NODES.objectNode()))
                        .build())
                .register();
        table.registry()
                .route("app/v1", "/things/(?P<id>[\\d]+)")
                .schema(() -> item)
                .endpoint(Endpoint.of("GET")
                        .handler(request -> RestResponse.ok(RestJson.NODES.objectNode()))
                        .build())
                .register();
        table.registry()
                .route("app/v1", "/hidden")
                .endpoint(Endpoint.of("GET")
                        .showInIndex(false)
                        .handler(request -> RestResponse.ok(RestJson.NODES.objectNode()))
                        .build())
                .register();
        List<RouteTable.Route> routes = table.routes();

        ObjectNode help = RouteData.of(routes.get(0), "help", URLS);
        assertThat(fieldNames(help)).containsExactly("namespace", "methods", "endpoints", "schema", "_links");
        assertThat(help.get("namespace").textValue()).isEqualTo("app/v1");
        // every shown endpoint's methods, repeats kept; the hidden endpoint is left out
        assertThat(help.get("methods").toString()).isEqualTo("[\"GET\",\"POST\",\"PUT\",\"PUT\"]");
        assertThat(help.at("/endpoints/0").toString())
                .startsWith("{\"methods\":[\"GET\"],\"allow_batch\":{\"v1\":true},\"args\":{\"context\":");
        // no args: an empty list, as PHP prints an empty array; no allow_batch when batching is not allowed
        assertThat(help.at("/endpoints/1").toString()).isEqualTo("{\"methods\":[\"POST\",\"PUT\"],\"args\":[]}");
        assertThat(help.get("endpoints")).hasSize(3);
        assertThat(help.get("schema").toString())
                .isEqualTo("{\"title\":\"thing\",\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\","
                        + "\"context\":[\"view\",\"edit\"]}}}");
        // the registered schema keeps its arg_options
        assertThat(item.at("/properties/id/arg_options").isObject()).isTrue();
        assertThat(help.get("_links").toString())
                .isEqualTo("{\"self\":[{\"href\":\"http://localhost/wp-json/app/v1/things\"}]}");

        ObjectNode view = RouteData.of(routes.get(0), "view", URLS);
        assertThat(fieldNames(view)).containsExactly("namespace", "methods", "endpoints", "_links");

        ObjectNode single = RouteData.of(routes.get(1), "help", URLS);
        assertThat(fieldNames(single)).containsExactly("namespace", "methods", "endpoints", "schema");
        assertThat(RouteData.of(routes.get(2), "help", URLS)).isNull();
    }

    @Test
    void collectionArgsAreWordPresssWithContextsFromTheSchemaDescending() throws Exception {
        ObjectNode item = (ObjectNode) RestJson.READER.readTree("{\"properties\":{\"a\":{\"context\":[\"edit\"]},"
                + "\"b\":{\"context\":[\"view\",\"embed\",\"edit\"]},\"c\":{\"type\":\"string\"}}}");
        List<Arg> args = Paging.collectionArgs(item, true);
        assertThat(args).extracting(Arg::name).containsExactly("context", "page", "per_page", "search", "offset");
        // the texts and keyword order of WordPress's /wp/v2/tags OPTIONS body (Part D 12)
        assertThat(RouteData.describe(args.get(0)).toString())
                .isEqualTo("{\"description\":\"Scope under which the request is made; determines fields present in"
                        + " response.\",\"type\":\"string\",\"enum\":[\"view\",\"embed\",\"edit\"],"
                        + "\"default\":\"view\",\"required\":false}");
        assertThat(RouteData.describe(args.get(1)).toString())
                .isEqualTo("{\"description\":\"Current page of the collection.\",\"type\":\"integer\",\"default\":1,"
                        + "\"minimum\":1,\"required\":false}");
        assertThat(RouteData.describe(args.get(2)).toString())
                .isEqualTo("{\"description\":\"Maximum number of items to be returned in result set.\","
                        + "\"type\":\"integer\",\"default\":10,\"minimum\":1,\"maximum\":100,\"required\":false}");
        assertThat(RouteData.describe(args.get(3)).toString())
                .isEqualTo("{\"description\":\"Limit results to those matching a string.\",\"type\":\"string\","
                        + "\"required\":false}");
        assertThat(RouteData.describe(args.get(4)).toString())
                .isEqualTo("{\"description\":\"Offset the result set by a specific number of items.\","
                        + "\"type\":\"integer\",\"required\":false}");
        assertThat(Paging.collectionArgs(item, false)).hasSize(4);
        // no contexts in the schema: no enum
        assertThat(Paging.collectionArgs(RestJson.NODES.objectNode(), false)
                        .get(0)
                        .schema()
                        .has("enum"))
                .isFalse();
    }

    @Test
    void offsetGoesWhereWordPresssTagsCollectionHasIt() throws Exception {
        // the first seven args of WordPress 7.1.3's OPTIONS /wp/v2/tags (Part D 12), byte for byte
        String tags = "{\"context\":{\"description\":\"Scope under which the request is made; determines fields"
                + " present in response.\",\"type\":\"string\",\"enum\":[\"view\",\"embed\",\"edit\"],"
                + "\"default\":\"view\",\"required\":false},\"page\":{\"description\":\"Current page of the"
                + " collection.\",\"type\":\"integer\",\"default\":1,\"minimum\":1,\"required\":false},"
                + "\"per_page\":{\"description\":\"Maximum number of items to be returned in result set.\","
                + "\"type\":\"integer\",\"default\":10,\"minimum\":1,\"maximum\":100,\"required\":false},"
                + "\"search\":{\"description\":\"Limit results to those matching a string.\",\"type\":\"string\","
                + "\"required\":false},\"exclude\":{\"description\":\"Ensure result set excludes specific IDs.\","
                + "\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"default\":[],\"required\":false},"
                + "\"include\":{\"description\":\"Limit result set to specific IDs.\",\"type\":\"array\","
                + "\"items\":{\"type\":\"integer\"},\"default\":[],\"required\":false},\"offset\":"
                + "{\"description\":\"Offset the result set by a specific number of items.\",\"type\":\"integer\","
                + "\"required\":false}}";
        ObjectNode item = (ObjectNode)
                RestJson.READER.readTree("{\"properties\":{\"id\":{\"context\":[\"view\",\"embed\",\"edit\"]}}}");
        RouteTable table = new RouteTable();
        table.registry()
                .route("wp/v2", "/tags")
                .endpoint(Endpoint.of("GET")
                        .args(Paging.collectionArgs(item, false))
                        .arg(ids("exclude", "Ensure result set excludes specific IDs."))
                        .arg(ids("include", "Limit result set to specific IDs."))
                        .arg(Paging.offsetArg())
                        .handler(request -> RestResponse.ok(RestJson.NODES.arrayNode()))
                        .build())
                .register();
        assertThat(RouteData.of(table.routes().get(0), "help", URLS)
                        .at("/endpoints/0/args")
                        .toString())
                .isEqualTo(tags);
        // withOffset appends it right after search
        assertThat(Paging.collectionArgs(item, true)).extracting(Arg::name).endsWith("search", "offset");
    }

    private static Arg ids(String name, String description) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("description", description);
        schema.put("type", "array");
        schema.putObject("items").put("type", "integer");
        schema.putArray("default");
        return new Arg(
                name, schema, false, Optional.of(RestJson.NODES.arrayNode()), Arg.Phase.SANITIZE, Optional.empty());
    }

    @Test
    void collectionArgsSanitizeAsWordPress() {
        List<Arg> args = Paging.collectionArgs(RestJson.NODES.objectNode(), true);
        assertThat(args.get(0).phase()).isEqualTo(Arg.Phase.VALIDATE);
        assertThat(args.get(0).defaultValue().orElseThrow().textValue()).isEqualTo("view");
        assertThat(sanitize(args.get(0), "Ed-i_t9!" + new String(Character.toChars(0xE9))))
                .isEqualTo("\"ed-i_t9\"");
        assertThat(sanitize(args.get(1), "-3")).isEqualTo("3");
        assertThat(sanitize(args.get(2), "1e2")).isEqualTo("100");
        assertThat(sanitize(args.get(3), " a  <b>x</b> ")).isEqualTo("\"a x\"");
        assertThat(args.get(4).sanitize()).isEmpty();
        assertThat(args.get(4).phase()).isEqualTo(Arg.Phase.SANITIZE);
        assertThat(args.get(1).defaultValue().orElseThrow().intValue()).isEqualTo(1);
        assertThat(args.get(2).defaultValue().orElseThrow().intValue()).isEqualTo(10);
    }

    private static String sanitize(Arg arg, String value) {
        return arg.sanitize()
                .orElseThrow()
                .apply(RestJson.NODES.textNode(value))
                .toString();
    }

    private static List<String> fieldNames(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
