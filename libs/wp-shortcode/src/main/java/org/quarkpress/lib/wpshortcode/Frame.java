package org.quarkpress.lib.wpshortcode;

import java.util.Objects;

/** The state of one apply call at one nesting depth; the context its handlers receive. */
final class Frame implements ShortcodeContext {

    private final Shortcodes owner;
    private final int depth;
    private final Frame root;
    private volatile boolean running = true;

    private Frame(Shortcodes owner, int depth, Frame root) {
        this.owner = owner;
        this.depth = depth;
        this.root = root == null ? this : root;
    }

    /** The frame of an outermost apply call. */
    static Frame root(Shortcodes owner) {
        return new Frame(owner, 0, null);
    }

    @Override
    public String expand(String content) {
        Objects.requireNonNull(content, "content");
        int next = depth + 1;
        if (next > owner.maxDepth()) {
            throw new ShortcodeException("shortcode expansion nested deeper than maxDepth " + owner.maxDepth());
        }
        return owner.expand(content, false, new Frame(owner, next, root));
    }

    @Override
    public int depth() {
        return depth;
    }

    @Override
    public boolean insideShortcodes() {
        return root.running;
    }

    /** Marks the outermost apply call as finished. */
    void finish() {
        running = false;
    }
}
