package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.RestResponse;

class RouteTableTest {

    @Test
    void routesLongerThan2048Utf8BytesAreNotMatched() {
        assertThat(RouteTable.tooLong("/" + "a".repeat(2047))).isFalse();
        assertThat(RouteTable.tooLong("/" + "a".repeat(2048))).isTrue();
        String eAcute = new String(Character.toChars(0xE9));
        // 1 + 2 * 1023 = 2047 bytes, then 2049
        assertThat(RouteTable.tooLong("/" + eAcute.repeat(1023))).isFalse();
        assertThat(RouteTable.tooLong("/" + eAcute.repeat(1024))).isTrue();
        String euro = new String(Character.toChars(0x20AC));
        assertThat(RouteTable.tooLong("/" + euro.repeat(682))).isFalse();
        assertThat(RouteTable.tooLong("/" + euro.repeat(683))).isTrue();
        String emoji = new String(Character.toChars(0x1F600));
        assertThat(RouteTable.tooLong("/" + emoji.repeat(511) + "abc")).isFalse();
        assertThat(RouteTable.tooLong("/" + emoji.repeat(512))).isTrue();
    }

    @Test
    void aSchemaWithPatternFailsRegistration() {
        ObjectNode pattern = RestJson.NODES.objectNode().put("type", "string").put("pattern", "^a");
        Arg patterned = Arg.of("a", "array").items(pattern).build();
        assertThatThrownBy(() -> new RouteTable()
                        .registry()
                        .route("app/v1", "/things")
                        .endpoint(endpoint(patterned))
                        .register())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported schema keyword: pattern");
        assertThatThrownBy(() -> new RouteTable()
                        .registry()
                        .route("app/v1", "/things")
                        .arg(patterned)
                        .endpoint(endpoint(Arg.of("b", "string").build()))
                        .register())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported schema keyword: pattern");
        ObjectNode item = RestJson.NODES.objectNode().put("type", "object");
        item.putObject("properties").set("name", pattern);
        RouteTable table = new RouteTable();
        assertThatThrownBy(() -> table.registry()
                        .route("app/v1", "/things")
                        .schema(() -> item)
                        .endpoint(endpoint(Arg.of("b", "string").build()))
                        .register())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported schema keyword: pattern");
        assertThat(table.routes()).isEmpty();
        table.registry()
                .route("app/v1", "/things")
                .endpoint(endpoint(Arg.of("b", "string").format("email").build()))
                .register();
        assertThat(table.routes()).hasSize(1);
    }

    @Test
    void eachNamespaceGetsItsIndexRightBeforeItsFirstRoute() {
        List<String> asked = new ArrayList<>();
        RouteTable table = new RouteTable(namespace -> {
            asked.add(namespace);
            return endpoint(Arg.of("namespace", null)
                    .defaultValue(TextNode.valueOf(namespace))
                    .build());
        });
        table.registry()
                .route("", "/")
                .endpoint(endpoint(Arg.of("context", null).build()))
                .register();
        table.registry()
                .route("a/v1", "/x")
                .endpoint(endpoint(Arg.of("x", "string").build()))
                .register();
        table.registry()
                .route("/b/v1/", "/y")
                .endpoint(endpoint(Arg.of("y", "string").build()))
                .register();
        table.registry()
                .route("a/v1", "/z")
                .endpoint(endpoint(Arg.of("z", "string").build()))
                .register();
        table.registry()
                .route("a/v1", "/x")
                .endpoint(endpoint(Arg.of("x2", "string").build()))
                .register();
        List<String> keys = new ArrayList<>();
        table.routes().forEach(route -> keys.add(route.key()));
        assertThat(keys).containsExactly("/", "/a/v1", "/a/v1/x", "/b/v1", "/b/v1/y", "/a/v1/z");
        // asked once per namespace, never for the root index's empty namespace
        assertThat(asked).containsExactly("a/v1", "b/v1");
        assertThat(table.namespaces()).containsExactly("a/v1", "b/v1");
        RouteTable.Route index = table.routes().get(1);
        assertThat(index.namespace()).isEqualTo("a/v1");
        assertThat(index.endpoints()).hasSize(1);
        assertThat(index.endpoints().get(0).args().get(0).defaultValue()).contains(TextNode.valueOf("a/v1"));
        // the index is matched like any route
        assertThat(table.match("/b/v1", "GET"))
                .map(match -> match.route().key())
                .contains("/b/v1");
    }

    @Test
    void withoutAnIndexNoNamespaceRouteIsAdded() {
        RouteTable table = new RouteTable();
        table.registry()
                .route("a/v1", "/x")
                .endpoint(endpoint(Arg.of("x", "string").build()))
                .register();
        assertThat(table.routes()).extracting(RouteTable.Route::key).containsExactly("/a/v1/x");
    }

    private static Endpoint endpoint(Arg arg) {
        return Endpoint.of("GET")
                .arg(arg)
                .handler(request -> RestResponse.ok(RestJson.NODES.objectNode()))
                .build();
    }
}
