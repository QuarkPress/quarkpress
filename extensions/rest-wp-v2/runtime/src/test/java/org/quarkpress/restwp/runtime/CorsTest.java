package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.RestResponse;

class CorsTest {

    static final List<String> CONFIGURED = List.of("https://admin.test", " null ");

    @Test
    void aConfiguredOriginGetsWordPresssOriginHeadersAndTheWiderLists() {
        assertThat(Cors.headers(Optional.of("https://admin.test"), false, CONFIGURED))
                .containsExactly(
                        Map.entry(
                                "Access-Control-Expose-Headers",
                                List.of("X-WP-Total, X-WP-TotalPages, Link, Allow, X-WP-Upload-Attachment-ID")),
                        Map.entry(
                                "Access-Control-Allow-Headers",
                                List.of("Authorization, X-WP-Nonce, Content-Disposition, Content-MD5, Content-Type,"
                                        + " X-HTTP-Method-Override")),
                        Map.entry("Access-Control-Allow-Origin", List.of("https://admin.test")),
                        Map.entry("Access-Control-Allow-Methods", List.of("OPTIONS, GET, POST, PUT, PATCH, DELETE")),
                        Map.entry("Access-Control-Allow-Credentials", List.of("true")),
                        Map.entry("Vary", List.of("Origin")));
        // the literal origin "null" is kept as it is, as WordPress keeps it
        assertThat(Cors.headers(Optional.of("null"), true, CONFIGURED))
                .containsEntry("Access-Control-Allow-Origin", List.of("null"));
        // ASCII case is ignored; the request's spelling is echoed
        assertThat(Cors.headers(Optional.of("HTTPS://Admin.Test"), false, CONFIGURED))
                .containsEntry("Access-Control-Allow-Origin", List.of("HTTPS://Admin.Test"));
    }

    @Test
    void noOtherOriginIsEverReflected() {
        String kelvin = "https://admin.tes" + new String(Character.toChars(0x212A));
        for (String origin : List.of(
                "https://evil.test",
                "https://admin.test\r\nSet-Cookie: x=1",
                "https://admin.test ",
                " https://admin.test",
                "https://admin.test, null",
                "https://admin.test,null",
                "https://admin.test.evil.test",
                kelvin,
                "https://admin.test\u0000")) {
            Map<String, List<String>> headers = Cors.headers(Optional.of(origin), false, CONFIGURED);
            assertThat(headers).as(origin).containsOnlyKeys("Vary");
        }
        // a configured entry that is no single origin never matches
        assertThat(Cors.headers(Optional.of("a"), false, List.of("a, b", "x\ny")))
                .isEmpty();
        assertThat(Cors.headers(Optional.of("x\ny"), false, List.of("a, b", "x\ny")))
                .isEmpty();
        // nothing configured: README D4, no origin at all
        assertThat(Cors.headers(Optional.of("https://admin.test"), false, List.of()))
                .isEmpty();
    }

    @Test
    void varyOriginForAnonymousGetsAndWheneverTheAnswerDependsOnTheOrigin() {
        assertThat(Cors.headers(Optional.empty(), true, List.of()))
                .containsExactly(Map.entry("Vary", List.of("Origin")));
        assertThat(Cors.headers(Optional.of(""), true, List.of()))
                .containsExactly(Map.entry("Vary", List.of("Origin")));
        assertThat(Cors.headers(Optional.empty(), false, List.of())).isEmpty();
        assertThat(Cors.headers(Optional.empty(), false, CONFIGURED)).isEmpty();
        assertThat(Cors.headers(Optional.of("https://x.test"), false, List.of()))
                .isEmpty();
        assertThat(Cors.headers(Optional.of("https://x.test"), true, List.of()))
                .containsExactly(Map.entry("Vary", List.of("Origin")));
        assertThat(Cors.headers(Optional.of("https://x.test"), false, CONFIGURED))
                .containsExactly(Map.entry("Vary", List.of("Origin")));
    }

    @Test
    void whitespaceRunsInHeaderValuesCollapseToOneSpace() {
        assertThat(ResponseHeaders.collapse("a  b")).isEqualTo("a b");
        assertThat(ResponseHeaders.collapse("a\r\nSet-Cookie: x")).isEqualTo("a Set-Cookie: x");
        assertThat(ResponseHeaders.collapse("\t a \u000b\u000c b \n")).isEqualTo(" a b ");
        assertThat(ResponseHeaders.collapse("a b c")).isEqualTo("a b c");
        assertThat(ResponseHeaders.collapse("")).isEmpty();
        // only PHP's \s: no-break space and other controls stay
        String nbsp = new String(Character.toChars(0xA0));
        String kept = "a" + nbsp + nbsp + "b\u0000";
        assertThat(ResponseHeaders.collapse(kept)).isEqualTo(kept);
        // one pass over the value, whatever its shape
        String spaces = " \t".repeat(500_000);
        assertThat(ResponseHeaders.collapse("x" + spaces + "y")).isEqualTo("x y");
    }

    @Test
    void theFixedHeadersAndTheJsonpRefusalAreWordPresss() {
        assertThat(ResponseHeaders.fixed("http://localhost/news/wp-json/"))
                .containsExactly(
                        Map.entry("Content-Type", List.of("application/json; charset=UTF-8")),
                        Map.entry("X-Robots-Tag", List.of("noindex")),
                        Map.entry("Link", List.of("<http://localhost/news/wp-json/>; rel=\"https://api.w.org/\"")),
                        Map.entry("X-Content-Type-Options", List.of("nosniff")),
                        Map.entry("Access-Control-Expose-Headers", List.of("X-WP-Total, X-WP-TotalPages, Link")),
                        Map.entry(
                                "Access-Control-Allow-Headers",
                                List.of("Authorization, X-WP-Nonce, Content-Disposition, Content-MD5, Content-Type")));
        assertThat(ResponseHeaders.fixed(null)).doesNotContainKey("Link").containsKey("X-Robots-Tag");
        assertThat(ResponseHeaders.jsonpRefusal("http://localhost/wp-json/"))
                .containsOnlyKeys("Content-Type", "X-Robots-Tag", "Link", "X-Content-Type-Options")
                .containsEntry("Content-Type", List.of("application/json; charset=UTF-8"));
        assertThat(ResponseHeaders.noCache())
                .containsExactly(
                        Map.entry("Expires", List.of("Wed, 11 Jan 1984 05:00:00 GMT")),
                        Map.entry("Cache-Control", List.of("no-cache, must-revalidate, max-age=0, no-store, private")));
    }

    @Test
    void theEnvelopeCarriesTheAnswersOwnHeaders() {
        RestResponse items = RestResponse.ok(RestJson.NODES.arrayNode())
                .withStatus(201)
                .withHeader("X-WP-Total", "12")
                .withHeader("X-WP-TotalPages", "x")
                .withHeader("Location", "http://localhost/wp-json/a")
                .withHeader("Allow", "GET");
        RestResponse wrapped = Envelope.wrap(items);
        assertThat(wrapped.status()).isEqualTo(200);
        assertThat(wrapped.headers()).isEmpty();
        assertThat(new String(RestJson.encode(wrapped.body(), false)))
                .isEqualTo("{\"body\":[],\"status\":201,\"headers\":{\"X-WP-Total\":12,\"X-WP-TotalPages\":\"x\","
                        + "\"Location\":\"http:\\/\\/localhost\\/wp-json\\/a\",\"Allow\":\"GET\"}}");
        assertThat(new String(RestJson.encode(
                        Envelope.wrap(new RestResponse(404, RestJson.NODES.objectNode(), Map.of(), Links.none()))
                                .body(),
                        false)))
                .isEqualTo("{\"body\":{},\"status\":404,\"headers\":[]}");
    }
}
