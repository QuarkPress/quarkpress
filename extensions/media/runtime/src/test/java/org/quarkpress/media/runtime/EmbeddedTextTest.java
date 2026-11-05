package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class EmbeddedTextTest {

    private static ObjectNode meta(String title, String caption, String alt) {
        return JsonNodeFactory.instance
                .objectNode()
                .put("title", title)
                .put("caption", caption)
                .put("alt", alt);
    }

    @Test
    void theEmbeddedTitleGate() {
        assertThat(EmbeddedText.of(meta("2024", "", "")).title()).isEmpty();
        assertThat(EmbeddedText.of(meta("0012", "", "")).title()).isEmpty();
        assertThat(EmbeddedText.of(meta("20250612", "", "")).title()).isEmpty();
        assertThat(EmbeddedText.of(meta("3.5", "", "")).title()).isEqualTo("3.5");
        assertThat(EmbeddedText.of(meta("IMG 4711", "", "")).title()).isEqualTo("IMG 4711");
        assertThat(EmbeddedText.of(meta("  ", "c", "")).title()).isEmpty();
        assertThat(EmbeddedText.of(meta(" Sunset ", "", "")).title()).isEqualTo("Sunset");
        assertThat(EmbeddedText.of(meta(" 2024 ", "", "")).title()).isEmpty();
        assertThat(EmbeddedText.of(meta("1e5", "", "")).title()).isEmpty();
    }

    @Test
    void captionAndAltAreTakenAsRead() {
        EmbeddedText text = EmbeddedText.of(meta("t", " c ", " a "));
        assertThat(text.caption()).isEqualTo(" c ");
        assertThat(text.alt()).isEqualTo(" a ");
        assertThat(EmbeddedText.of(JsonNodeFactory.instance.objectNode())).isEqualTo(new EmbeddedText("", "", ""));
    }
}
