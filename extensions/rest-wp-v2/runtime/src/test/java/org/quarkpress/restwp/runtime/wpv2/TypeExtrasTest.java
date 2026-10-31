package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import org.junit.jupiter.api.Test;

class TypeExtrasTest {

    @Test
    void theBuiltInTypesHaveWordPresssRecordedValues() {
        // Part D 20
        assertThat(TypeExtras.of("post").icon()).isEqualTo("dashicons-admin-post");
        assertThat(TypeExtras.of("page").icon()).isEqualTo("dashicons-admin-page");
        assertThat(TypeExtras.of("attachment").icon()).isEqualTo("dashicons-admin-media");
        assertThat(TypeExtras.of("wp_block").icon()).isNull();
        for (String type : new String[] {"post", "page", "attachment", "wp_block"}) {
            TypeExtras extras = TypeExtras.of(type);
            assertThat(extras.template().toString()).as(type).isEqualTo("[]");
            assertThat(extras.templateLock()).as(type).isEqualTo(BooleanNode.FALSE);
            assertThat(extras.restNamespace()).as(type).isEqualTo("wp/v2");
        }
        assertThat(TypeExtras.of("post").supportArguments().get("editor").toString())
                .isEqualTo("[{\"notes\":true}]");
        assertThat(TypeExtras.of("page").supportArguments().get("editor").toString())
                .isEqualTo("[{\"notes\":true}]");
        assertThat(TypeExtras.of("attachment").supportArguments()).isEmpty();
        assertThat(TypeExtras.of("wp_block").supportArguments()).isEmpty();
    }

    @Test
    void anyOtherTypeHasWordPresssDefaults() {
        TypeExtras book = TypeExtras.of("book");
        assertThat(book.icon()).isNull();
        assertThat(book.template().toString()).isEqualTo("[]");
        assertThat(book.templateLock()).isEqualTo(BooleanNode.FALSE);
        assertThat(book.restNamespace()).isEqualTo("wp/v2");
        assertThat(book.supportArguments()).isEmpty();
    }

    @Test
    void theValuesAreNotShared() {
        ((ArrayNode) TypeExtras.of("post").template()).add(1);
        assertThat(TypeExtras.of("post").template().toString()).isEqualTo("[]");
    }
}
