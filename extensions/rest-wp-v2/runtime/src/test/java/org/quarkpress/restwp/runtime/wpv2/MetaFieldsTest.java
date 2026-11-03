package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.quarkpress.posts.MetaKeyDefinition;

/** The REST schema of a registered meta key. */
class MetaFieldsTest {

    @Test
    void objectSchemasAreClosedAtEveryDepthWhateverFormTheirTypeTakes() {
        ObjectNode overlay = JsonNodeFactory.instance.objectNode();
        ObjectNode properties = overlay.putObject("properties");
        ObjectNode nested = properties.putObject("nested");
        nested.putArray("type").add("object").add("null");
        nested.putObject("properties").putObject("deep").put("type", "object");
        ObjectNode items = properties.putObject("list");
        items.put("type", "array");
        items.putObject("items").put("type", "object");
        ObjectNode open = properties.putObject("open");
        open.put("type", "object");
        open.put("additionalProperties", true);
        MetaKeyDefinition key = MetaKeyDefinition.builder("qp_shape")
                .type("object")
                .schema(overlay)
                .single()
                .build();

        ObjectNode schema = MetaFields.keySchema(key);

        assertThat(schema.at("/additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/properties/nested/additionalProperties").asBoolean())
                .isFalse();
        assertThat(schema.at("/properties/nested/properties/deep/additionalProperties")
                        .asBoolean())
                .isFalse();
        assertThat(schema.at("/properties/list/items/additionalProperties").asBoolean())
                .isFalse();
        assertThat(schema.at("/properties/open/additionalProperties").asBoolean())
                .isTrue();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("title").asText()).isEmpty();
    }
}
