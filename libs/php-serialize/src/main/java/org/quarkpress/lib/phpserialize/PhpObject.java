package org.quarkpress.lib.phpserialize;

import java.util.Objects;

/**
 * PHP object, serialized as {@code O:len:"ClassName":N:{properties}}. The class name is data; this
 * library never looks it up or instantiates anything.
 */
public record PhpObject(String className, PhpArray properties) implements PhpValue {
    public PhpObject {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(properties, "properties");
    }
}
