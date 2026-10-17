package org.quarkpress.lib.wpshortcode;

/** Thrown when nested {@link ShortcodeContext#expand} calls exceed {@link ShortcodeOptions#maxDepth()}. */
public final class ShortcodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ShortcodeException(String message) {
        super(message);
    }
}
