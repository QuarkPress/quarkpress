package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RestCallMultipartTest {

    static String text(RestCall call) {
        return new String(call.body().orElseThrow(), StandardCharsets.UTF_8);
    }

    @Test
    void theFilePartComesFirstThenTheFieldsInMapOrder() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("generate_sub_sizes", "false");
        fields.put("title", "A title");
        RestCall call = RestCall.multipart(
                "alice",
                "/wp-json/wp/v2/media",
                "x.jpg",
                "image/jpeg",
                "BYTES".getBytes(StandardCharsets.US_ASCII),
                fields);
        String b = RestCall.MULTIPART_BOUNDARY;
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.contentType()).contains("multipart/form-data; boundary=" + b);
        assertThat(text(call))
                .isEqualTo("--" + b + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"x.jpg\"\r\n"
                        + "Content-Type: image/jpeg\r\n\r\nBYTES\r\n"
                        + "--" + b + "\r\nContent-Disposition: form-data; name=\"generate_sub_sizes\"\r\n\r\nfalse\r\n"
                        + "--" + b + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nA title\r\n"
                        + "--" + b + "--\r\n");
    }

    @Test
    void quotesAndLineBreaksInNamesArePercentEncoded() {
        RestCall call =
                RestCall.multipart("alice", "/m", "a\"b\r\nc.jpg", "image/jpeg", new byte[] {1}, Map.of("x\"y", "v"));
        assertThat(text(call)).contains("filename=\"a%22b%0D%0Ac.jpg\"").contains("name=\"x%22y\"");
    }

    @Test
    void aBoundaryInsideTheBytesIsReplacedByASuffixedOne() {
        byte[] bytes = ("x--" + RestCall.MULTIPART_BOUNDARY + "y").getBytes(StandardCharsets.US_ASCII);
        RestCall call = RestCall.multipart("alice", "/m", "a.bin", "application/octet-stream", bytes, Map.of());
        assertThat(call.contentType()).contains("multipart/form-data; boundary=" + RestCall.MULTIPART_BOUNDARY + "-1");
        assertThat(text(call)).startsWith("--" + RestCall.MULTIPART_BOUNDARY + "-1\r\n");
    }

    @Test
    void aBoundaryInsideAFieldValueIsAlsoAvoided() {
        RestCall call = RestCall.multipart(
                "alice", "/m", "a.bin", "text/plain", new byte[0], Map.of("t", RestCall.MULTIPART_BOUNDARY));
        assertThat(call.contentType()).contains("multipart/form-data; boundary=" + RestCall.MULTIPART_BOUNDARY + "-1");
    }

    @Test
    void aContentTypeWithALineBreakIsRefused() {
        assertThatThrownBy(() -> RestCall.multipart("alice", "/m", "a", "text/plain\r\nX: y", new byte[0], Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
