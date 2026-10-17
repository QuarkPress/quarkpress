package org.quarkpress.lib.wpshortcode;

/**
 * Processor options.
 *
 * @param maxDepth how deeply {@link ShortcodeContext#expand} calls may nest (0 to 256); WordPress has no limit
 */
public record ShortcodeOptions(int maxDepth) {

    public static final int MAX_DEPTH_CAP = 256;

    public static final ShortcodeOptions DEFAULTS = new ShortcodeOptions(64);

    public ShortcodeOptions {
        if (maxDepth < 0 || maxDepth > MAX_DEPTH_CAP) {
            throw new IllegalArgumentException("maxDepth must be between 0 and " + MAX_DEPTH_CAP + ": " + maxDepth);
        }
    }

    public ShortcodeOptions withMaxDepth(int maxDepth) {
        return new ShortcodeOptions(maxDepth);
    }
}
