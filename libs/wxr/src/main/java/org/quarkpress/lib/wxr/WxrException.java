package org.quarkpress.lib.wxr;

/** Reading stopped: a limit was exceeded, or strict mode met something lenient mode would repair. */
public final class WxrException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final WxrProblem.Kind kind;
    private final long line;
    private final long column;

    public WxrException(WxrProblem.Kind kind, long line, long column, String message) {
        this(kind, line, column, message, null);
    }

    public WxrException(WxrProblem.Kind kind, long line, long column, String message, Throwable cause) {
        super(kind + " at line " + line + ", column " + column + ": " + message, cause);
        this.kind = kind;
        this.line = line;
        this.column = column;
    }

    public WxrProblem.Kind kind() {
        return kind;
    }

    public long line() {
        return line;
    }

    public long column() {
        return column;
    }
}
