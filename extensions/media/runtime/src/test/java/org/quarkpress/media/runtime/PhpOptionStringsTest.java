package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigInteger;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.persistence.NetworkOptions;

/**
 * Stored option values as PHP's string conversion writes them (checked with PHP 8.4's {@code (string)} cast), and the
 * network's list of upload types read through it.
 */
class PhpOptionStringsTest {

    static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void scalarsAreWrittenAsPhpWritesThem() {
        assertThat(PhpOptionStrings.of(JSON.textNode("jpg png"))).isEqualTo("jpg png");
        assertThat(PhpOptionStrings.of(JSON.numberNode(100))).isEqualTo("100");
        assertThat(PhpOptionStrings.of(JSON.numberNode(123456789012345678L))).isEqualTo("123456789012345678");
        assertThat(PhpOptionStrings.of(JSON.numberNode(1.5))).isEqualTo("1.5");
        assertThat(PhpOptionStrings.of(JSON.numberNode(1.0))).isEqualTo("1");
        assertThat(PhpOptionStrings.of(JSON.numberNode(0.1 + 0.2))).isEqualTo("0.3");
        assertThat(PhpOptionStrings.of(JSON.numberNode(1e15))).isEqualTo("1.0E+15");
        // beyond PHP's integers a JSON number is a float
        assertThat(PhpOptionStrings.of(JSON.numberNode(BigInteger.TWO.pow(64)))).isEqualTo("1.844674407371E+19");
        assertThat(PhpOptionStrings.of(JSON.booleanNode(true))).isEqualTo("1");
        assertThat(PhpOptionStrings.of(JSON.booleanNode(false))).isEmpty();
    }

    @Test
    void nullsArraysAndObjectsHaveNoString() {
        assertThat(PhpOptionStrings.of(JSON.nullNode())).isNull();
        assertThat(PhpOptionStrings.of(JSON.arrayNode().add("jpg"))).isNull();
        assertThat(PhpOptionStrings.of(JSON.objectNode().put("a", "jpg"))).isNull();
    }

    @Test
    void theNetworksUploadTypes() {
        assertThat(types(true, JSON.textNode("jpg pdf"))).contains("jpg pdf");
        assertThat(types(true, JSON.numberNode(7))).contains("7");
        assertThat(types(true, JSON.numberNode(2.5))).contains("2.5");
        assertThat(types(true, JSON.booleanNode(true))).contains("1");
        assertThat(types(true, JSON.booleanNode(false))).contains("");
        // unset, null or an array: WordPress's fallback list
        assertThat(types(true, null)).contains("jpg jpeg png gif");
        assertThat(types(true, JSON.nullNode())).contains("jpg jpeg png gif");
        assertThat(types(true, JSON.arrayNode().add("pdf"))).contains("jpg jpeg png gif");
        // a single site has no such list
        assertThat(types(false, JSON.textNode("jpg pdf"))).isEmpty();
    }

    private static Optional<String> types(boolean multisite, JsonNode stored) {
        NetworkUploadTypes types = new NetworkUploadTypes();
        types.installMode = () -> multisite;
        types.network = new NetworkOptions() {
            @Override
            public Optional<JsonNode> find(String name) {
                return name.equals("upload_filetypes") ? Optional.ofNullable(stored) : Optional.empty();
            }
        };
        return types.list();
    }
}
