package org.quarkpress.multisite.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.SiteRequest;

class SiteRequestsTest {

    @Test
    void theTargetIsKeptAsReceived() {
        assertThat(SiteRequests.target("/a/../b?x=%2e")).isEqualTo("/a/../b?x=%2e");
        assertThat(SiteRequests.target("")).isEmpty();
        assertThat(SiteRequests.target(null)).isEmpty();
    }

    @Test
    void anAbsoluteTargetLosesItsAuthority() {
        assertThat(SiteRequests.target("http://example.org/news/x?y=1")).isEqualTo("/news/x?y=1");
        assertThat(SiteRequests.target("http://example.org?y=1")).isEqualTo("/?y=1");
        assertThat(SiteRequests.target("http://example.org")).isEmpty();
        assertThat(SiteRequests.target("http://example.org/a/../b")).isEqualTo("/a/../b");
    }

    @Test
    void otherFormsHaveNoPath() {
        assertThat(SiteRequests.target("*")).isEmpty();
        assertThat(SiteRequests.target("host:443")).isEmpty();
        assertThat(SiteRequests.target("http:/x")).isEmpty();
    }

    /** A request with only the parts SiteRequests reads: scheme, target and authority (the Host header). */
    private static HttpServerRequest request(String uri, String host, int port) {
        return (HttpServerRequest) Proxy.newProxyInstance(
                HttpServerRequest.class.getClassLoader(),
                new Class<?>[] {HttpServerRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "scheme" -> "http";
                    case "uri" -> uri;
                    case "authority" -> HostAndPort.create(host, port);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void anOriginFormTargetIsResolvedWhateverTheProxySetting() {
        SiteRequest expected = new SiteRequest("http", "example.org", 8080, "/news/x");
        assertThat(SiteRequests.of(request("/news/x", "example.org", 8080), false))
                .contains(expected);
        assertThat(SiteRequests.of(request("/news/x", "example.org", 8080), true))
                .contains(expected);
    }

    @Test
    void anAbsoluteFormTargetMustNameTheHostHeadersHost() {
        assertThat(SiteRequests.of(request("http://Example.org:81/a?b=1", "example.org", 80), false))
                .contains(new SiteRequest("http", "example.org", 80, "/a?b=1"));
        assertThat(SiteRequests.of(request("http://other.org/a", "example.org", 80), false))
                .isEmpty();
        assertThat(SiteRequests.of(request("http://user@example.org/a", "example.org", 80), false))
                .isPresent();
    }

    @Test
    void anAbsoluteFormTargetIsRefusedUnderProxyForwarding() {
        // the setting is passed in by the filter, which reads it at run time
        assertThat(SiteRequests.of(request("http://example.org/a", "example.org", 80), true))
                .isEmpty();
    }

    @Test
    void aTargetOfAnotherFormHasNoSite() {
        assertThat(SiteRequests.of(request("*", "example.org", 80), false)).contains(SiteRequest.none());
        assertThat(SiteRequests.of(request("", "example.org", 80), false)).contains(SiteRequest.none());
    }
}
