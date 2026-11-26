package org.quarkword.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkword.testing.wporacle.ParityRun;
import org.quarkword.testing.wporacle.ParitySkipList;

/** Front-end links and the alternate {@code Link} header compared as they are; the namespaces only one side lists. */
class RestComparisonTest {

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void frontEndLinksAreComparedAsTheyAre() {
        // equal links compare equal; a pretty link and a plain one differ like any other value
        String pretty = "{\"id\":5,\"type\":\"post\",\"slug\":\"hello-world\","
                + "\"link\":\"http://localhost/s1/2026/11/03/hello-world/\","
                + "\"permalink_template\":\"http://localhost/s1/2026/11/03/%postname%/\"}";
        String same = "{\"id\":9,\"type\":\"post\",\"slug\":\"hello-world\","
                + "\"link\":\"http://localhost/s1/2026/11/03/hello-world/\","
                + "\"permalink_template\":\"http://localhost/s1/2026/11/03/%postname%/\"}";
        String plain = "{\"id\":9,\"type\":\"post\",\"slug\":\"hello-world\",\"link\":\"http://localhost/s1/?p=9\","
                + "\"permalink_template\":\"http://localhost/s1/?p=9\"}";
        assertThat(aspects("/wp-json/wp/v2/posts/5", pretty, same, Map.of("p:a", 5L), Map.of("p:a", 9L)))
                .isEmpty();
        assertThat(aspects("/wp-json/wp/v2/posts/5", pretty, plain, Map.of("p:a", 5L), Map.of("p:a", 9L)))
                .containsExactly("req1:body:/link", "req1:body:/permalink_template");
        // a search item's url likewise
        assertThat(aspects(
                        "/wp-json/wp/v2/search?search=x",
                        "[{\"id\":2,\"url\":\"http://localhost/s1/category/uncategorized/\",\"type\":\"category\"}]",
                        "[{\"id\":3,\"url\":\"http://localhost/s1/?cat=3\",\"type\":\"category\"}]",
                        Map.of("t:uncategorized", 2L),
                        Map.of("t:uncategorized", 3L)))
                .containsExactly("req1:body:/0/url");
    }

    @Test
    void theAlternateLinkHeaderIsComparedAsItIs() {
        RestCall item = RestCall.get("anonymous", "/wp-json/wp/v2/posts/5");
        List<Exchange> wp = List.of(new Exchange(
                item,
                200,
                Map.of("Link", List.of("<http://localhost/s1/hello-world/>; rel=\"alternate\"; type=text/html")),
                bytes("{\"id\":5}")));
        List<Exchange> same = List.of(new Exchange(
                item,
                200,
                Map.of("Link", List.of("<http://localhost/s1/hello-world/>; rel=\"alternate\"; type=text/html")),
                bytes("{\"id\":9}")));
        List<Exchange> plain = List.of(new Exchange(
                item,
                200,
                Map.of("Link", List.of("<http://localhost/s1/?p=9>; rel=\"alternate\"; type=text/html")),
                bytes("{\"id\":9}")));
        assertThat(mismatches(List.of(item), wp, same, Map.of("p:a", 5L), Map.of("p:a", 9L)))
                .isEmpty();
        assertThat(mismatches(List.of(item), wp, plain, Map.of("p:a", 5L), Map.of("p:a", 9L)))
                .containsExactly("req1:header:link");
    }

    @Test
    void theQuarkWordOnlyNamespaceIsItsOwnAspect() {
        String wp = "{\"namespaces\":[\"wp-block-editor/v1\",\"wp/v2\",\"wp-site-health/v1\"],\"routes\":{}}";
        assertThat(aspects(
                        "/wp-json/",
                        wp,
                        "{\"namespaces\":[\"wp/v2\",\"quarkword/v1\"],\"routes\":{}}",
                        Map.of(),
                        Map.of()))
                .containsExactlyInAnyOrder(
                        "req1:body:wp-only:/namespaces/wp-block-editor~1v1",
                        "req1:body:wp-only:/namespaces/wp-site-health~1v1",
                        "req1:body:qw-only:/namespaces/quarkword~1v1");
        // any other namespace QuarkWord lists still differs
        assertThat(aspects(
                        "/wp-json/",
                        wp,
                        "{\"namespaces\":[\"wp/v2\",\"quarkword/v1\",\"other/v1\"],\"routes\":{}}",
                        Map.of(),
                        Map.of()))
                .contains("req1:body:/namespaces/1");
    }

    /** The mismatching aspects of comparing one request's two bodies. */
    static List<String> aspects(
            String target, String wordpress, String quarkword, Map<String, Long> wpRefs, Map<String, Long> qpRefs) {
        RestCall call = RestCall.get("anonymous", target);
        return mismatches(
                List.of(call),
                List.of(new Exchange(call, 200, Map.of(), bytes(wordpress))),
                List.of(new Exchange(call, 200, Map.of(), bytes(quarkword))),
                wpRefs,
                qpRefs);
    }

    static List<String> mismatches(
            List<RestCall> calls,
            List<Exchange> wordpress,
            List<Exchange> quarkword,
            Map<String, Long> wpRefs,
            Map<String, Long> qpRefs) {
        ParityRun run = new ParityRun("t", ParitySkipList.empty(), Path.of("target", "comparison-test.json"));
        new RestComparison(wpRefs, qpRefs, "/s1/")
                .compare(new RestScenario("s1", "test", List.of(), calls), wordpress, quarkword, run);
        List<String> out = new ArrayList<>();
        run.mismatches().forEach(m -> out.add(m.aspect()));
        return out;
    }
}
