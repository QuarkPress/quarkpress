package org.quarkpress.restwp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.runtime.FieldsFilter;
import org.quarkpress.restwp.runtime.RestJson;

/** The work a controller's field selection takes under a hostile {@code _fields}. */
class FieldsTest {

    @Test
    void aHostileFieldListCostsItsLengthOnceAndEachCheckItsOwnLength() {
        ObjectNode schema = RestJson.NODES.objectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("id").putArray("context").add("view");
        for (int i = 0; i < 29; i++) {
            properties.putObject("property" + i).putArray("context").add("view");
        }
        for (int count : new int[] {16_000, 160_000, 1_600_000}) {
            String text = "title,".repeat(count) + "property7.raw";
            long[] parsing = new long[1];
            List<String> fields = FieldsFilter.parse(TextNode.valueOf(text), parsing);
            long[] selecting = new long[1];
            Fields.Selection selection = Fields.select(fields, schema, TextNode.valueOf("view"), false, selecting);
            assertThat(selection.fields()).containsExactly("property7.raw", "id");
            long[] checking = new long[1];
            int calls = 0;
            boolean anyOther = false;
            for (int item = 0; item < 100; item++) {
                for (int i = 0; i < 29; i++) {
                    anyOther |= selection.includes("property" + i, checking) && i != 7;
                    calls++;
                }
                assertThat(selection.includes("id", checking)).isTrue();
                assertThat(selection.includes("property7", checking)).isTrue();
                assertThat(selection.includes("_links", checking)).isFalse();
                calls += 3;
            }
            assertThat(anyOther).isFalse();
            assertThat(parsing[0]).as("parsing %d fields", count).isLessThanOrEqualTo(2L * text.length());
            assertThat(selecting[0]).as("selecting from %d fields", count).isLessThanOrEqualTo(3L * text.length());
            // each check costs at most the checked field's length plus one per segment, whatever was requested
            assertThat(checking[0]).as("checks over %d fields", count).isLessThanOrEqualTo(calls * 12L);
        }
    }

    @Test
    void aDeepFieldCostsItsLength() {
        String deep = "a.".repeat(200_000) + "a";
        long[] work = new long[1];
        Fields.Selection selection = Fields.Selection.of(List.of(deep), work);
        assertThat(work[0]).isLessThanOrEqualTo(2L * deep.length());
        work[0] = 0;
        assertThat(selection.includes(deep, work)).isTrue();
        assertThat(selection.includes("a", work)).isTrue();
        assertThat(selection.includes(deep + ".b", work)).isTrue();
        assertThat(selection.includes("b", work)).isFalse();
        assertThat(work[0]).isLessThanOrEqualTo(5L * deep.length());
    }
}
