package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.runtime.RestJson;

/** WordPress's reading of a slug list: a list's scalar items, or a text split on commas and whitespace. */
class ListArgsTest {

    @Test
    void slugListSplitsSanitizesAndDeduplicates() {
        assertThat(ListArgs.slugList(
                        TextNode.valueOf("Publish,future  draft,,publish"), s -> s.toLowerCase(Locale.ROOT)))
                .containsExactly("publish", "future", "draft");
        ArrayNode list = RestJson.NODES.arrayNode().add("a").add(7).add("a");
        assertThat(ListArgs.slugList(list, s -> s)).containsExactly("a", "7");
        assertThat(ListArgs.slugList(TextNode.valueOf(""), s -> s)).isEmpty();
    }

    @Test
    void slugListDropsWhatSanitizingEmptiesAndNestedOrNullItems() {
        ArrayNode list = RestJson.NODES.arrayNode().add("keep").add(NullNode.getInstance());
        list.addArray().add("nested");
        list.add("drop");
        assertThat(ListArgs.slugList(list, s -> s.equals("drop") ? "" : s)).containsExactly("keep");
        assertThat(ListArgs.slugList(null, s -> s)).isEmpty();
        assertThat(ListArgs.slugList(NullNode.getInstance(), s -> s)).isEmpty();
        // the text's separators are commas and PHP's whitespace, runs of them count once
        assertThat(ListArgs.slugList(TextNode.valueOf(" a\t,\nb\r\n c "), s -> s))
                .containsExactly("a", "b", "c");
    }

    @Test
    void slugListHoldsAtMostTheListBound() {
        StringBuilder text = new StringBuilder();
        ArrayNode list = RestJson.NODES.arrayNode();
        for (int i = 0; i < ListArgs.MAX_ITEMS + 1; i++) {
            text.append("s").append(i).append(',');
            list.add("s" + i);
        }
        assertThat(ListArgs.slugList(TextNode.valueOf(text.toString()), s -> s))
                .hasSize(ListArgs.MAX_ITEMS)
                .last()
                .isEqualTo("s" + (ListArgs.MAX_ITEMS - 1));
        assertThat(ListArgs.slugList(list, s -> s)).hasSize(ListArgs.MAX_ITEMS);
    }
}
