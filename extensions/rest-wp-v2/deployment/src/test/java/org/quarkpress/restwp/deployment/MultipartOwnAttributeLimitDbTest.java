package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** An application's own limit on one form attribute wins over rest-wp-v2's default: a longer text part is refused. */
class MultipartOwnAttributeLimitDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    MultipartOwnAttributeLimitDbTest.class,
                    MultipartBodyDbTest.class,
                    RawBodyDbTest.class,
                    RawBodyDbTest.Streamed.class,
                    RawBodyDbTest.Streamed.Reply.class,
                    RawBodyDbTest.Generated.class,
                    TestRoutes.class,
                    TestRoutes.PlainResource.class)
            .overrideConfigKey("quarkus.http.limits.max-form-attribute-size", "4K");

    @Test
    void aTextPartAboveTheOwnLimitIsRefused() {
        RawBodyDbTest.Streamed.Reply reply = MultipartBodyDbTest.multipart(
                "POST", MultipartBodyDbTest.textPart("description", "d".repeat(100 * 1024)));
        assertThat(reply.status()).as(reply.head()).isEqualTo(413);
        // within the limit, it is a parameter
        RawBodyDbTest.Streamed.Reply within =
                MultipartBodyDbTest.multipart("POST", MultipartBodyDbTest.textPart("description", "d".repeat(4000)));
        assertThat(within.json().get("values").get("description").asText()).hasSize(4000);
    }
}
