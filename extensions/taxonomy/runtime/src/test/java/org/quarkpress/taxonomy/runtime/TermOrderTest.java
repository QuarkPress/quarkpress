package org.quarkpress.taxonomy.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quarkpress.taxonomy.TermOrder;

class TermOrderTest {

    @Test
    void orderByNamesMapAsWordPressMapsThem() {
        assertThat(TermOrder.parse("name")).isEqualTo(TermOrder.NAME);
        assertThat(TermOrder.parse("NAME")).isEqualTo(TermOrder.NAME);
        assertThat(TermOrder.parse("SLUG")).isEqualTo(TermOrder.SLUG);
        assertThat(TermOrder.parse("Term_Order")).isEqualTo(TermOrder.TERM_ORDER);
        assertThat(TermOrder.parse("ID")).isEqualTo(TermOrder.ID);
        assertThat(TermOrder.parse("slug")).isEqualTo(TermOrder.SLUG);
        assertThat(TermOrder.parse("term_group")).isEqualTo(TermOrder.TERM_GROUP);
        assertThat(TermOrder.parse("term_id")).isEqualTo(TermOrder.ID);
        assertThat(TermOrder.parse("id")).isEqualTo(TermOrder.ID);
        assertThat(TermOrder.parse("")).isEqualTo(TermOrder.ID);
        assertThat(TermOrder.parse("term_taxonomy_id")).isEqualTo(TermOrder.ID);
        assertThat(TermOrder.parse("count")).isEqualTo(TermOrder.COUNT);
        assertThat(TermOrder.parse("parent")).isEqualTo(TermOrder.PARENT);
        assertThat(TermOrder.parse("taxonomy")).isEqualTo(TermOrder.TAXONOMY);
        assertThat(TermOrder.parse("description")).isEqualTo(TermOrder.DESCRIPTION);
        assertThat(TermOrder.parse("include")).isEqualTo(TermOrder.INCLUDE);
        assertThat(TermOrder.parse("slug__in")).isEqualTo(TermOrder.SLUG_IN);
        assertThat(TermOrder.parse("term_order")).isEqualTo(TermOrder.TERM_ORDER);
        assertThat(TermOrder.parse("none")).isEqualTo(TermOrder.NONE);
        assertThat(TermOrder.parse("rand")).isEqualTo(TermOrder.NAME);
        assertThat(TermOrder.parse(null)).isEqualTo(TermOrder.ID);
    }
}
