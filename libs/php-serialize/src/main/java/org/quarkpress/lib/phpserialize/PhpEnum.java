package org.quarkpress.lib.phpserialize;

import java.util.Objects;

/** PHP 8.1 enum case, serialized as {@code E:len:"Class:Case";}. */
public record PhpEnum(String className, String caseName) implements PhpValue {
    public PhpEnum {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(caseName, "caseName");
    }
}
