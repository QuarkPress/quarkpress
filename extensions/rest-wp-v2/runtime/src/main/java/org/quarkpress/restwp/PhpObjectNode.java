package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * A JSON object that WordPress builds as a PHP object ({@code stdClass}) rather than an array, such as a post type's
 * {@code labels}. The response filters treat it as WordPress treats such an object: the {@code _fields} reduction
 * keeps it whole (a requested {@code labels.name} keeps every label), and the context filter keeps it an object
 * (an emptied one stays {@code {}}, integer keys are never turned into a list). It prints like any object.
 */
// Jackson declares deepCopy generic on JsonNode; overriding it with a concrete type is reported as unchecked
@SuppressWarnings("unchecked")
public final class PhpObjectNode extends ObjectNode {

    private static final long serialVersionUID = 1L;

    public PhpObjectNode() {
        super(JsonNodeFactory.instance);
    }

    /** A PHP object with {@code object}'s entries (the values shared, not copied). */
    public static PhpObjectNode of(ObjectNode object) {
        PhpObjectNode copy = new PhpObjectNode();
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            copy.set(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    /** A deep copy that is still a PHP object. */
    @Override
    public ObjectNode deepCopy() {
        PhpObjectNode copy = new PhpObjectNode();
        for (Map.Entry<String, JsonNode> entry : properties()) {
            copy.set(entry.getKey(), entry.getValue().deepCopy());
        }
        return copy;
    }
}
