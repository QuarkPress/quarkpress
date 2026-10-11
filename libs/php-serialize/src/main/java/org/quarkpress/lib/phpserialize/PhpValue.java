package org.quarkpress.lib.phpserialize;

/**
 * A value in the PHP {@code serialize()} data model.
 *
 * <p>Values are immutable. The tree is produced by {@link PhpSerialize#parse(byte[])} and consumed
 * by {@link PhpSerialize#serialize(PhpValue)}. No implementation ever instantiates a class named in
 * the data.
 */
public sealed interface PhpValue
        permits PhpNull,
                PhpBool,
                PhpInt,
                PhpFloat,
                PhpString,
                PhpArray,
                PhpObject,
                PhpCustomObject,
                PhpReference,
                PhpEnum {

    /**
     * Converts to plain Java objects: {@code null}, {@link Boolean}, {@link Long}, {@link Double},
     * {@link String}, {@link java.util.List} (for list-shaped arrays) or {@link java.util.LinkedHashMap}
     * with {@link String} keys (for other arrays and objects).
     */
    default Object toJavaObject() {
        return PhpConversions.toJavaObject(this);
    }

    /** Renders this value as compact JSON text. */
    default String toJson() {
        return PhpJson.write(this);
    }
}
