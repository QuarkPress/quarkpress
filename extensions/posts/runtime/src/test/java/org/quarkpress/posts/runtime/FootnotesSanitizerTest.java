package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.posts.MetaContext;

class FootnotesSanitizerTest {

    private static final MetaContext CONTEXT = new MetaContext(PostId.of(1), "post", "footnotes", null);

    private static String sanitize(String text) {
        return sanitize(TextNode.valueOf(text));
    }

    private static String sanitize(JsonNode value) {
        return new FootnotesSanitizer(context -> false).sanitize(value, CONTEXT).textValue();
    }

    private static String sanitizeWithUnfilteredHtml(String text) {
        return new FootnotesSanitizer(context -> true)
                .sanitize(TextNode.valueOf(text), CONTEXT)
                .textValue();
    }

    @Test
    void reencodesLikePhpAndDropsScripts() {
        assertThat(sanitize("[{\"id\":\"A b\",\"content\":\"<script>x</script>/y\"},{\"id\":\"\",\"content\":\"z\"}]"))
                .isEqualTo(
                        "[{\"id\":\"ab\",\"content\":\"x\\/y\"}]"); // id key-sanitized; kses; entry without id dropped
        // a JSON object is a PHP array too: its values are the entries, kept ones listed again
        assertThat(sanitize("{}")).isEqualTo("[]");
        assertThat(sanitize("{\"x\":{\"id\":\"a\",\"content\":\"b\"}}"))
                .isEqualTo("[{\"id\":\"a\",\"content\":\"b\"}]");
        assertThat(sanitize("{\"id\":\"a\"}")).isEqualTo("[]"); // its value is no entry
        assertThat(sanitizeWithUnfilteredHtml("<b>raw</b>")).isEqualTo("<b>raw</b>");
    }

    @Test
    void entriesNeedAnIdAndContentThatAreNotEmptyInPhpsSense() {
        String eAcute = new String(Character.toChars(0xE9));
        String given = "[{\"id\":\"0\",\"content\":\"x\"},{\"id\":\"a\",\"content\":\"0\"},{\"id\":\"a\"},"
                + "{\"content\":\"a\"},{\"id\":5,\"content\":\"y\"},\"z\",7,null,[\"id\"],"
                + "{\"id\":\"a\",\"content\":\"" + eAcute + "/\",\"extra\":1},{\"id\":true,\"content\":false}]";
        assertThat(sanitize(given))
                .isEqualTo("[{\"id\":\"5\",\"content\":\"y\"},{\"id\":\"a\",\"content\":\"\\u00e9\\/\"}]");
        assertThat(sanitize("[]")).isEqualTo("[]");
    }

    @Test
    void textThatIsNotAListBecomesEmpty() {
        assertThat(sanitize("not json")).isEqualTo("");
        assertThat(sanitize("")).isEqualTo("");
        assertThat(sanitize("5")).isEqualTo("");
        assertThat(sanitize("[] trailing")).isEqualTo("");
        assertThat(sanitize(IntNode.valueOf(5))).isEqualTo("");
        assertThat(sanitize(NullNode.instance)).isEqualTo("");
    }
}
