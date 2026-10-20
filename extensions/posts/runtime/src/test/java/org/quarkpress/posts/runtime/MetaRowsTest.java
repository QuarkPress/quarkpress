package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MetaRowsTest {

    @Test
    void addAppendsAndUniqueRefusesAKeyThatHasValues() {
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        assertThat(MetaRows.add(meta, "color", TextNode.valueOf("red"), false)).isTrue();
        assertThat(MetaRows.add(meta, "color", TextNode.valueOf("red"), false)).isTrue();
        assertThat(MetaRows.add(meta, "color", TextNode.valueOf("blue"), true)).isFalse();
        assertThat(MetaRows.values(meta, "color")).containsExactly(TextNode.valueOf("red"), TextNode.valueOf("red"));
        assertThat(meta.toString()).isEqualTo("{\"color\":[\"red\",\"red\"]}");
    }

    @Test
    void updateAddsChangesEveryValueAndReportsNoChange() {
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        assertThat(MetaRows.update(meta, "size", IntNode.valueOf(1))).isTrue();
        assertThat(MetaRows.update(meta, "size", IntNode.valueOf(1))).isFalse();
        MetaRows.add(meta, "size", IntNode.valueOf(2), false);
        assertThat(MetaRows.update(meta, "size", IntNode.valueOf(3))).isTrue();
        assertThat(MetaRows.values(meta, "size")).containsExactly(IntNode.valueOf(3), IntNode.valueOf(3));
        assertThat(MetaRows.update(meta, "size", IntNode.valueOf(3))).isFalse();
    }

    @Test
    void deleteRemovesAKeyOrMatchingValues() {
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        MetaRows.add(meta, "tag", TextNode.valueOf("a"), false);
        MetaRows.add(meta, "tag", TextNode.valueOf("b"), false);
        MetaRows.add(meta, "tag", TextNode.valueOf("a"), false);
        assertThat(MetaRows.delete(meta, "tag", TextNode.valueOf("a"))).isTrue();
        assertThat(MetaRows.values(meta, "tag")).containsExactly(TextNode.valueOf("b"));
        assertThat(MetaRows.delete(meta, "tag", TextNode.valueOf("z"))).isFalse();
        assertThat(MetaRows.delete(meta, "tag")).isTrue();
        assertThat(meta.has("tag")).isFalse();
        assertThat(MetaRows.delete(meta, "tag")).isFalse();
    }

    @Test
    void deleteWithAnEmptyNullOrFalseValueDeletesEveryValue() {
        for (JsonNode all : Arrays.asList(TextNode.valueOf(""), NullNode.instance, BooleanNode.FALSE, null)) {
            ObjectNode meta = JsonNodeFactory.instance.objectNode();
            MetaRows.add(meta, "tag", TextNode.valueOf("a"), false);
            MetaRows.add(meta, "tag", TextNode.valueOf("b"), false);
            assertThat(MetaRows.delete(meta, "tag", all)).isTrue();
            assertThat(meta.has("tag")).isFalse();
            assertThat(MetaRows.delete(meta, "tag", all)).isFalse();
        }
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        MetaRows.add(meta, "n", IntNode.valueOf(0), false);
        MetaRows.add(meta, "n", IntNode.valueOf(1), false);
        assertThat(MetaRows.delete(meta, "n", IntNode.valueOf(0))).isTrue();
        assertThat(MetaRows.values(meta, "n")).containsExactly(IntNode.valueOf(1));
    }

    @Test
    void emptyKeysAndZeroAreNotKeys() {
        assertThat(MetaRows.validKey("")).isFalse();
        assertThat(MetaRows.validKey("0")).isFalse();
        assertThat(MetaRows.validKey("_wp_old_slug")).isTrue();
    }

    @Test
    void keysStartingWithAnUnderscoreAfterNonPrintableCharactersAreProtected() {
        String zeroWidthSpace = new String(Character.toChars(0x200B));
        String control = new String(Character.toChars(0x01));
        assertThat(MetaRows.protectedKey("_secret")).isTrue();
        assertThat(MetaRows.protectedKey(zeroWidthSpace + "_secret")).isTrue();
        assertThat(MetaRows.protectedKey(control + zeroWidthSpace + "_secret")).isTrue();
        assertThat(MetaRows.protectedKey("secret")).isFalse();
        assertThat(MetaRows.protectedKey("a_secret")).isFalse();
        assertThat(MetaRows.protectedKey(" _secret")).isFalse();
        assertThat(MetaRows.protectedKey("")).isFalse();
        assertThat(MetaRows.protectedKey(zeroWidthSpace + control)).isFalse();
    }
}
