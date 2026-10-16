package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class WordPressJsonTest {

    @Test
    void escapesLikeSerializeBlockAttributes() { // D14, D15
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("s", "a\\b \" -- --- < > & / é \u2028 \u0001 \u001f \t /");
        assertThat(WordPressJson.write(n))
                .isEqualTo("{\"s\":\"a\\u005cb \\u0022 \\u002d\\u002d \\u002d\\u002d- \\u003c \\u003e \\u0026 / é"
                        + " \\u2028 \\u0001 \\u001f \\t /\"}");
    }

    @Test
    void controlCharactersAndDeleteAreWrittenAsPhpWritesThem() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("c", "\u0000\u007f\b");
        assertThat(WordPressJson.write(n)).isEqualTo("{\"c\":\"\\u0000\u007f\\b\"}");
    }

    @Test
    void numbersAreWrittenAsPhpJsonEncodeWritesThem() { // D15
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("f", 1.0)
                .put("e", 100.0)
                .put("g", 1.5e300)
                .put("z", -0.0)
                .put("h", 0.1)
                .put("t", 1e20)
                .put("i", 0);
        n.put("l", Long.MAX_VALUE);
        n.putArray("a").add(1.0).add(-0.0).add(1e20);
        assertThat(WordPressJson.write(n))
                .isEqualTo("{\"f\":1,\"e\":100,\"g\":1.5e+300,\"z\":-0,\"h\":0.1,\"t\":1.0e+20,\"i\":0,"
                        + "\"l\":9223372036854775807,\"a\":[1,-0,1.0e+20]}");
    }

    @Test
    void integersBeyondSixtyFourBitsStayExact() { // D10, a documented difference: PHP writes 1.2345678901234567e+19
        ObjectNode n = JsonNodeFactory.instance.objectNode().put("n", new BigInteger("12345678901234567890"));
        assertThat(WordPressJson.write(n)).isEqualTo("{\"n\":12345678901234567890}");
    }

    @Test
    void nonFiniteNumbersAreWrittenAsNull() { // D11, a documented difference: WordPress drops all attributes
        ObjectNode n = JsonNodeFactory.instance.objectNode().put("x", Double.POSITIVE_INFINITY);
        assertThat(WordPressJson.write(n)).isEqualTo("{\"x\":null}");
    }

    @Test
    void keysAreEscapedLikeValues() {
        ObjectNode n = JsonNodeFactory.instance.objectNode().put("a--b<", true);
        assertThat(WordPressJson.write(n)).isEqualTo("{\"a\\u002d\\u002db\\u003c\":true}");
    }
}
