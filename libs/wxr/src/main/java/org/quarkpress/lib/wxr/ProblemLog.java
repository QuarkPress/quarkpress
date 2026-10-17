package org.quarkpress.lib.wxr;

import java.util.ArrayList;
import java.util.List;

/** The problems of one read. Stores at most {@link #MAX_STORED} (plus one TOO_MANY_PROBLEMS marker); counts all. */
final class ProblemLog {

    static final int MAX_STORED = 1000;

    private final boolean lenient;
    private final List<WxrProblem> problems = new ArrayList<>();
    private long count;
    private WxrException failure;

    ProblemLog(boolean lenient) {
        this.lenient = lenient;
    }

    boolean lenient() {
        return lenient;
    }

    /** A repair: recorded in lenient mode; in strict mode remembered as the failure and thrown. */
    void repair(WxrProblem.Kind kind, long line, long column, String detail) {
        if (!lenient) {
            failure = new WxrException(kind, line, column, detail);
            throw failure;
        }
        note(kind, line, column, detail);
    }

    /** A notice, recorded in both modes. */
    void note(WxrProblem.Kind kind, long line, long column, String detail) {
        count++;
        if (problems.size() < MAX_STORED) {
            problems.add(new WxrProblem(kind, line, column, detail));
        } else if (problems.size() == MAX_STORED) {
            problems.add(new WxrProblem(
                    WxrProblem.Kind.TOO_MANY_PROBLEMS, line, column, "further problems are counted, not stored"));
        }
    }

    /** The strict-mode refusal, also when a parser wrapped or swallowed the exception thrown for it. */
    WxrException failure() {
        return failure;
    }

    List<WxrProblem> problems() {
        return List.copyOf(problems);
    }

    long count() {
        return count;
    }
}
