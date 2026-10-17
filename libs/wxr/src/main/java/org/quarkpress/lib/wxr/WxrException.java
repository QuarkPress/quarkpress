package org.quarkpress.lib.wxr;

/** Reading stopped: a limit was exceeded, or strict mode met something lenient mode would repair. */
public final class WxrException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final WxrProblem.Kind kind;
    private final long line;
    private final long column;

    /** A refusal of {@code kind} at {@code line}:{@code column} (-1 when unknown). */
    WxrException(WxrProblem.Kind kind, long line, long column, String message) {
        this(kind, line, column, message, null);
    }

    /** A refusal of {@code kind} at {@code line}:{@code column} (-1 when unknown), caused by {@code cause}. */
    WxrException(WxrProblem.Kind kind, long line, long column, String message, Throwable cause) {
        super(kind + " at line " + line + ", column " + column + ": " + message, cause);
        this.kind = kind;
        this.line = line;
        this.column = column;
    }

    /** What stopped reading: the problem lenient mode would have recorded, or the limit that was exceeded. */
    public WxrProblem.Kind kind() {
        return kind;
    }

    /** The 1-based line where reading stopped, or -1 when unknown. */
    public long line() {
        return line;
    }

    /** The 1-based column where reading stopped, or -1 when unknown. */
    public long column() {
        return column;
    }
}
