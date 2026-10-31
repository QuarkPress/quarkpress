package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import io.quarkus.security.AuthenticationFailedException;
import java.io.UncheckedIOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.TermId;
import org.quarkpress.restwp.RestError;
import org.quarkpress.taxonomy.TermException;

class RestErrorTest {

    @Test
    void termExistsCarriesItsHistoryInAdditionalData() {
        RestError error = RestError.noStatus(
                        "term_exists", "A term with the name provided already exists in this taxonomy.")
                .withData(IntNode.valueOf(7))
                .withData(IntNode.valueOf(7))
                .withData(json("{\"status\":400,\"term_id\":7}"));
        assertThat(error.toJson().toString())
                .isEqualTo(
                        "{\"code\":\"term_exists\",\"message\":\"A term with the name provided"
                                + " already exists in this taxonomy.\",\"data\":{\"status\":400,\"term_id\":7},\"additional_data\":[7,7]}");
        assertThat(error.status()).isEqualTo(400);
    }

    @Test
    void anErrorWithoutStatusIs500AndPrintsNullData() {
        RestError error = RestError.noStatus("empty_term_name", "A name is required for this term.");
        assertThat(error.toJson().toString())
                .isEqualTo(
                        "{\"code\":\"empty_term_name\",\"message\":\"A name is required for this term.\",\"data\":null}");
        assertThat(error.status()).isEqualTo(500);
    }

    @Test
    void furtherErrorsAreListedAndTheLastStatusWins() {
        RestError error = RestError.of("a", "A.", 400).withError(RestError.of("b", "B.", 403));
        assertThat(error.toJson().at("/additional_errors/0/code").asText()).isEqualTo("b");
        assertThat(error.status()).isEqualTo(403);
    }

    @Test
    void aDuplicateTermNameMapsToTheSameHistory() {
        RestError error = ErrorMapping.toError(TermException.exists(new TermId(7), false), null);
        assertThat(error.toJson().toString())
                .isEqualTo(
                        "{\"code\":\"term_exists\",\"message\":\"A term with the name provided"
                                + " already exists in this taxonomy.\",\"data\":{\"status\":400,\"term_id\":7},\"additional_data\":[7,7]}");
        RestError flat = ErrorMapping.toError(TermException.notHierarchical(), null);
        assertThat(flat.toJson().toString())
                .isEqualTo("{\"code\":\"rest_taxonomy_not_hierarchical\",\"message\":\"Cannot set parent term, taxonomy"
                        + " is not hierarchical.\",\"data\":{\"status\":400}}");
        RestError other = ErrorMapping.toError(TermException.missingParent(), null);
        assertThat(other.toJson().toString())
                .isEqualTo("{\"code\":\"missing_parent\",\"message\":\"Parent term does not exist.\",\"data\":null}");
        assertThat(other.status()).isEqualTo(500);
        // a duplicate name without the existing term's id has no history
        RestError bare = ErrorMapping.toError(new TermException("term_exists", "Dup.", "", Optional.empty()), null);
        assertThat(bare.toJson().toString())
                .isEqualTo("{\"code\":\"term_exists\",\"message\":\"Dup.\",\"data\":{\"status\":400}}");
    }

    @Test
    void aFailedAuthenticationInsideTheServerIsTheTokenError() {
        RestError error = ErrorMapping.toError(new AuthenticationFailedException("expired"), "GET", "/x");
        assertThat(error.toJson().toString())
                .isEqualTo("{\"code\":\"rest_invalid_token\",\"message\":\"The access token is invalid or has"
                        + " expired.\",\"data\":{\"status\":401}}");
    }

    @Test
    void anUnexpectedFailureIsAFixedFatalErrorAndItsLogLineHasNoRawControlCharacters() {
        RestError error = ErrorMapping.toError(new IllegalStateException("secret detail"), "GET", "/x");
        assertThat(error.toJson().toString())
                .isEqualTo("{\"code\":\"internal_server_error\",\"message\":\"There has been a critical error on"
                        + " this website.\",\"data\":{\"status\":500}}");
        String forged = "/wp/v2/a\r\n2026-10-30 ERROR forged\u0000\u007f\u0085\u2028\\";
        String line = ErrorMapping.logLine("GET", forged);
        assertThat(line)
                .isEqualTo(
                        "REST request GET /wp/v2/a\\x0d\\x0a2026-10-30 ERROR forged\\x00\\x7f\\x85\\u2028\\\\ failed");
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            assertThat(c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == 0x2028 || c == 0x2029)
                    .as("character %d", i)
                    .isFalse();
        }
    }

    private static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
