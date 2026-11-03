package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

class PhpJsonTextTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Test
    void encodesAsPhpDoesByDefault() {
        String text = "a/b " + new String(Character.toChars(0xE9)) + new String(Character.toChars(0x1F600)) + "\"\t";
        assertThat(PhpJsonText.encode(TextNode.valueOf(text))).isEqualTo("\"a\\/b \\u00e9\\ud83d\\ude00\\\"\\t\"");
    }

    @Test
    void controlCharactersUseTheShortEscapesPhpUsesAndDeleteStaysRaw() {
        String text = "\b\f\n\r\t\u0000\u001f\u007f<>&'\\";
        assertThat(PhpJsonText.encode(TextNode.valueOf(text)))
                .isEqualTo("\"\\b\\f\\n\\r\\t\\u0000\\u001f\u007f<>&'\\\\\"");
    }

    @Test
    void containersAndScalarsFollowPhp() {
        ObjectNode object = NODES.objectNode();
        object.put("b", 1);
        ArrayNode list = object.putArray("a");
        list.add("x").add(2.5).add(true).addNull().add(NODES.objectNode()).add(NODES.arrayNode());
        assertThat(PhpJsonText.encode(object)).isEqualTo("{\"b\":1,\"a\":[\"x\",2.5,true,null,{},[]]}");
    }

    @Test
    void doublesAreWrittenAsPhpWritesThem() {
        assertThat(PhpJsonText.encode(DoubleNode.valueOf(1.0))).isEqualTo("1");
        assertThat(PhpJsonText.encode(DoubleNode.valueOf(-0.0))).isEqualTo("-0");
        assertThat(PhpJsonText.encode(DoubleNode.valueOf(12345.678))).isEqualTo("12345.678");
        assertThat(PhpJsonText.encode(DoubleNode.valueOf(0.0001))).isEqualTo("0.0001");
        assertThat(PhpJsonText.encode(DoubleNode.valueOf(0.00001))).isEqualTo("1.0e-5");
        assertThat(PhpJsonText.encode(DoubleNode.valueOf(1e16))).isEqualTo("10000000000000000");
        assertThat(PhpJsonText.encode(DoubleNode.valueOf(1e17))).isEqualTo("1.0e+17");
        assertThat(PhpJsonText.encode(DoubleNode.valueOf(123456789012345678.0))).isEqualTo("1.2345678901234568e+17");
        assertThat(PhpJsonText.encode(DoubleNode.valueOf(1e20))).isEqualTo("1.0e+20");
        assertThatThrownBy(() -> PhpJsonText.encode(DoubleNode.valueOf(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
