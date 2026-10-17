package org.quarkpress.lib.wxr;

import java.util.Objects;

/**
 * How {@code WxrReader} reads. {@link #LENIENT} (the default) repairs what it can and records each repair as a
 * {@link WxrProblem}; {@link #STRICT} throws {@link WxrException} instead. Both bound every value to {@link
 * #maxCharsPerValue()} characters and refuse DTDs.
 */
public final class WxrReadOptions {

    /** 512 Mi characters. */
    public static final int DEFAULT_MAX_CHARS_PER_VALUE = 512 * 1024 * 1024;

    /**
     * Repairs and records: the default. Values up to {@link #DEFAULT_MAX_CHARS_PER_VALUE} chars, any number of items,
     * any URI bound to {@code wp} or {@code excerpt} accepted, gzip detected.
     */
    public static final WxrReadOptions LENIENT =
            new WxrReadOptions(true, DEFAULT_MAX_CHARS_PER_VALUE, Long.MAX_VALUE, true, true);

    /**
     * Throws {@link WxrException} where lenient mode would repair. Same limits as {@link #LENIENT}; only WordPress's
     * own namespace URIs are accepted; gzip detected.
     */
    public static final WxrReadOptions STRICT =
            new WxrReadOptions(false, DEFAULT_MAX_CHARS_PER_VALUE, Long.MAX_VALUE, false, true);

    private final boolean lenient;
    private final int maxCharsPerValue;
    private final long maxItems;
    private final boolean acceptAnyWpNamespace;
    private final boolean gzipAutoDetect;

    private WxrReadOptions(
            boolean lenient,
            int maxCharsPerValue,
            long maxItems,
            boolean acceptAnyWpNamespace,
            boolean gzipAutoDetect) {
        this.lenient = lenient;
        this.maxCharsPerValue = maxCharsPerValue;
        this.maxItems = maxItems;
        this.acceptAnyWpNamespace = acceptAnyWpNamespace;
        this.gzipAutoDetect = gzipAutoDetect;
    }

    /** True when problems are repaired and recorded, false when they throw {@link WxrException}. */
    public boolean lenient() {
        return lenient;
    }

    /**
     * The most characters one value (a text node, CDATA section, comment, attribute value or name) may hold. The
     * limit is per value, not per entity: an entity can hold many values. A value costs roughly 3 to 6 bytes of heap
     * per character (Latin-1 strings against UTF-16 ones, plus the copy made while it is assembled). The default is
     * generous; callers that read untrusted files should lower it to what their largest legitimate value needs.
     */
    public int maxCharsPerValue() {
        return maxCharsPerValue;
    }

    /**
     * The most items a file may have; reading the next one throws {@link WxrException} of kind
     * {@link WxrProblem.Kind#TOO_MANY_ITEMS}, in lenient mode too. Unbounded ({@code Long.MAX_VALUE}) by default.
     */
    public long maxItems() {
        return maxItems;
    }

    /** Whether any URI bound to the {@code wp} or {@code excerpt} prefix counts as WordPress's. */
    public boolean acceptAnyWpNamespace() {
        return acceptAnyWpNamespace;
    }

    /** Whether a stream starting with the gzip magic bytes is decompressed. */
    public boolean gzipAutoDetect() {
        return gzipAutoDetect;
    }

    /**
     * A copy with another per-value limit (see {@link #maxCharsPerValue()}).
     *
     * @throws IllegalArgumentException when {@code maxCharsPerValue} is not positive
     */
    public WxrReadOptions withMaxCharsPerValue(int maxCharsPerValue) {
        if (maxCharsPerValue <= 0) {
            throw new IllegalArgumentException("maxCharsPerValue must be positive: " + maxCharsPerValue);
        }
        return new WxrReadOptions(lenient, maxCharsPerValue, maxItems, acceptAnyWpNamespace, gzipAutoDetect);
    }

    /**
     * A copy with another item limit (see {@link #maxItems()}); 0 refuses the first item.
     *
     * @throws IllegalArgumentException when {@code maxItems} is negative
     */
    public WxrReadOptions withMaxItems(long maxItems) {
        if (maxItems < 0) {
            throw new IllegalArgumentException("maxItems must not be negative: " + maxItems);
        }
        return new WxrReadOptions(lenient, maxCharsPerValue, maxItems, acceptAnyWpNamespace, gzipAutoDetect);
    }

    /** A copy that does or does not accept any URI bound to {@code wp} or {@code excerpt} as WordPress's. */
    public WxrReadOptions withAcceptAnyWpNamespace(boolean acceptAnyWpNamespace) {
        return new WxrReadOptions(lenient, maxCharsPerValue, maxItems, acceptAnyWpNamespace, gzipAutoDetect);
    }

    /** A copy that does or does not decompress a stream starting with the gzip magic bytes. */
    public WxrReadOptions withGzipAutoDetect(boolean gzipAutoDetect) {
        return new WxrReadOptions(lenient, maxCharsPerValue, maxItems, acceptAnyWpNamespace, gzipAutoDetect);
    }

    /** Equal when every setting is equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof WxrReadOptions other
                && lenient == other.lenient
                && maxCharsPerValue == other.maxCharsPerValue
                && maxItems == other.maxItems
                && acceptAnyWpNamespace == other.acceptAnyWpNamespace
                && gzipAutoDetect == other.gzipAutoDetect;
    }

    @Override
    public int hashCode() {
        return Objects.hash(lenient, maxCharsPerValue, maxItems, acceptAnyWpNamespace, gzipAutoDetect);
    }

    @Override
    public String toString() {
        return "WxrReadOptions[lenient=" + lenient + ", maxCharsPerValue=" + maxCharsPerValue + ", maxItems="
                + maxItems + ", acceptAnyWpNamespace=" + acceptAnyWpNamespace + ", gzipAutoDetect=" + gzipAutoDetect
                + "]";
    }
}
