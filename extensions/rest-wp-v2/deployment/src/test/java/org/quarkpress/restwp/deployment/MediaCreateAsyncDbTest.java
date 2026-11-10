package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * An upload in the {@code async} processing mode answers 201 with the stored attachment before its sizes exist:
 * {@code media_details} empty and every registered size missing, the header and {@code Location} as in the default
 * mode. The author carol uploads on the main site.
 */
class MediaCreateAsyncDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(MediaCreateAsyncDbTest.class).overrideConfigKey("quarkpress.media.processing", "async");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static AgroalDataSource data;
    static UserId carol;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        carol = TestData.memberOfMain(data, "author");
    }

    @Test
    void anAsyncUploadAnswersThePendingAttachment() throws IOException {
        Response answer = Http.as("test-" + carol.value())
                .multiPart("file", "async.jpg", MediaFixtures.jpeg(1200, 800), "image/jpeg")
                .multiPart("alt_text", "A")
                .post("/wp-json/wp/v2/media");
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(201);
        JsonNode body = MAPPER.readTree(answer.asString());
        long id = body.get("id").longValue();
        assertThat(answer.header("X-WP-Upload-Attachment-ID")).isEqualTo(Long.toString(id));
        assertThat(answer.header("Location")).isEqualTo("http://localhost/wp-json/wp/v2/media/" + id);
        assertThat(body.get("media_details").toString()).isEqualTo("{}");
        assertThat(body.get("alt_text").asText()).isEqualTo("A");
        List<String> missing = new ArrayList<>();
        body.get("missing_image_sizes").forEach(size -> missing.add(size.asText()));
        assertThat(missing).containsExactly("thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048");
    }
}
