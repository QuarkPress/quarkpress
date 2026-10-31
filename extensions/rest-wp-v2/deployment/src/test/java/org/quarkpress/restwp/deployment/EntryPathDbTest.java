package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.QuarkusExtensionTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** With a non-default REST application path, REST URLs stay where WordPress has them. */
class EntryPathDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    EntryPathDbTest.class,
                    TestRoutes.class,
                    TestRoutes.PlainResource.class,
                    RawHttp.class,
                    RawHttp.Reply.class)
            .overrideConfigKey("quarkus.rest.path", "/api");

    @Test
    void restUrlsAreServedWhateverTheRestApplicationPath() {
        for (String url : List.of("/wp-json/qp-test/v1/echo/5", "/?rest_route=/qp-test/v1/echo/5")) {
            RawHttp.Reply reply = RawHttp.get(url);
            assertThat(reply.status()).as(url).isEqualTo(200);
            assertThat(reply.text()).as(url).contains("\"urlId\":\"5\"");
        }
        // the application's own resources moved below /api
        Http.as(null).get("/api/it-plain/x/wp-json/ping").then().statusCode(200).body(equalTo("pong"));
    }

    @Test
    void theEntryCannotBeReachedDirectly() {
        for (String target : List.of("/api/_quarkpress/rest-wp-v2/route", "/_quarkpress/rest-wp-v2/route")) {
            RawHttp.Reply reply = RawHttp.get(target);
            assertThat(reply.status()).as(target).isEqualTo(404);
            assertThat(reply.text()).as(target).doesNotContain("urlId");
        }
    }
}
