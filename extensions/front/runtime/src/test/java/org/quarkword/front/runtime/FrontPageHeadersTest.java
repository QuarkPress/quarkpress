package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkword.front.FrontAnswer;
import org.quarkword.front.FrontRenderer;

/** A renderer's headers replace the headers the front end computed of the same name, ignoring case. */
class FrontPageHeadersTest {

    /** A renderer that sets its own cache policy and adds a header, with lower-case names. */
    private static final FrontRenderer STUB = (request, viewer, password) -> new FrontAnswer(
            200,
            Map.of("cache-control", List.of("public, max-age=60"), "x-theme", List.of("stub")),
            "page".getBytes(StandardCharsets.UTF_8));

    @Test
    void aRendererHeaderReplacesTheComputedOneIgnoringCase() {
        Map<String, List<String>> computed = new LinkedHashMap<>();
        computed.put("Content-Type", List.of("text/html; charset=UTF-8"));
        computed.put("Cache-Control", List.of("no-cache, must-revalidate, max-age=0, no-store, private"));
        computed.put("Expires", List.of("Wed, 11 Jan 1984 05:00:00 GMT"));

        FrontAnswer answer = FrontPage.withComputedHeaders(
                computed, STUB.render(null, null, FrontRenderer.PasswordState.NOT_REQUIRED));

        assertThat(answer.headers())
                .containsOnlyKeys("Content-Type", "Expires", "cache-control", "x-theme")
                .containsEntry("cache-control", List.of("public, max-age=60"))
                .containsEntry("Expires", List.of("Wed, 11 Jan 1984 05:00:00 GMT"));
        assertThat(answer.status()).isEqualTo(200);
        assertThat(new String(answer.body(), StandardCharsets.UTF_8)).isEqualTo("page");
    }
}
