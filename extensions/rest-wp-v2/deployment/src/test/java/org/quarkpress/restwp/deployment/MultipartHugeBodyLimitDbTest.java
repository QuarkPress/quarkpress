package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** An application whose body limit is 2 GiB or more starts, and a multipart text part reaches the handler. */
class MultipartHugeBodyLimitDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    MultipartHugeBodyLimitDbTest.class,
                    MultipartBodyDbTest.class,
                    RawBodyDbTest.class,
                    RawBodyDbTest.Streamed.class,
                    RawBodyDbTest.Streamed.Reply.class,
                    RawBodyDbTest.Generated.class,
                    TestRoutes.class,
                    TestRoutes.PlainResource.class)
            .overrideConfigKey("quarkus.http.limits.max-body-size", "3G");

    @Test
    void aSmallTextPartIsAParameter() {
        RawBodyDbTest.Streamed.Reply reply =
                MultipartBodyDbTest.multipart("POST", MultipartBodyDbTest.textPart("post", "11"));
        assertThat(reply.status()).as(reply.head()).isEqualTo(200);
        assertThat(reply.json().get("values").toString()).isEqualTo("{\"post\":\"11\"}");
    }
}
