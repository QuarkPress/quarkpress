package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Below a non-default HTTP root path, the re-route reaches the entry resource under that root. */
class EntryRootPathDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.with(
                    EntryRootPathDbTest.class, TestRoutes.class, RawHttp.class, RawHttp.Reply.class)
            .overrideConfigKey("quarkus.http.root-path", "/app");

    @Test
    void aQueryRouteIsServedBelowTheRootPath() {
        RawHttp.Reply reply = RawHttp.get("/app/?rest_route=/qp-test/v1/echo/5");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.text()).contains("\"urlId\":\"5\"");
        RawHttp.Reply direct = RawHttp.get("/app/_quarkpress/rest-wp-v2/route");
        assertThat(direct.status()).isEqualTo(404);
        assertThat(direct.text()).doesNotContain("urlId");
    }
}
