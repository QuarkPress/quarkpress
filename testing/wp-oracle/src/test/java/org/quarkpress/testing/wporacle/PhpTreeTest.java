package org.quarkpress.testing.wporacle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhpTreeTest {

    @Test
    void encodesTheDocumentedShapes() throws Exception {
        JsonNode expected =
                OracleJson.MAPPER.readTree("{\"t\":\"array\",\"e\":[[{\"t\":\"int\",\"v\":\"-5\"},{\"t\":\"null\"}],"
                        + "[{\"t\":\"string\",\"b64\":\"YQ==\"},{\"t\":\"float\",\"v\":\"0.1\"}],"
                        + "[{\"t\":\"append\"},{\"t\":\"bool\",\"v\":true}]]}");
        JsonNode actual = PhpTree.array(List.of(
                new PhpTree.Entry(PhpTree.integer(-5), PhpTree.nul()),
                new PhpTree.Entry(PhpTree.string("a"), PhpTree.floating("0.1")),
                new PhpTree.Entry(PhpTree.append(), PhpTree.bool(true))));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void stringsCarryBytes() {
        byte[] bytes = {0, (byte) 0xFF, 'a'};
        assertThat(PhpTree.bytes(PhpTree.string(bytes))).containsExactly(bytes);
        assertThat(PhpTree.type(PhpTree.string(bytes))).isEqualTo("string");
    }

    @Test
    void objectsEnumsAndMarkers() throws Exception {
        assertThat(PhpTree.object("stdClass", List.of()))
                .isEqualTo(OracleJson.MAPPER.readTree("{\"t\":\"object\",\"c\":\"stdClass\",\"e\":[]}"));
        assertThat(PhpTree.enumCase("Suit", "Hearts"))
                .isEqualTo(OracleJson.MAPPER.readTree("{\"t\":\"enum\",\"c\":\"Suit\",\"v\":\"Hearts\"}"));
        assertThat(PhpTree.custom("Foo", new byte[] {'h', 'i'}))
                .isEqualTo(OracleJson.MAPPER.readTree("{\"t\":\"custom\",\"c\":\"Foo\",\"b64\":\"aGk=\"}"));
        assertThat(PhpTree.type(PhpTree.cycle())).isEqualTo("cycle");
        assertThat(PhpTree.type(PhpTree.failed())).isEqualTo("failed");
    }

    @Test
    void mapperReadsVeryDeepTrees() throws Exception {
        String deep = "[".repeat(5_000) + "]".repeat(5_000);
        assertThat(OracleJson.MAPPER.readTree(deep).isArray()).isTrue();
    }
}
