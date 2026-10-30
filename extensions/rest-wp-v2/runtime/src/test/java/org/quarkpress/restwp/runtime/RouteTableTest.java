package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private static Endpoint endpoint(Arg arg) {
        return Endpoint.of("GET")
                .arg(arg)
                .handler(request -> RestResponse.ok(RestJson.NODES.objectNode()))
                .build();
    }
}
