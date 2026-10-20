package org.quarkpress.posts.deployment;

/**
 * Collects the messages of a failure and its causes and suppressed exceptions.
 * Same as the security deployment test helper of that name.
 */
public final class Failures {

    private Failures() {}

    public static String messages(Throwable failure) {
        StringBuilder text = new StringBuilder();
        append(failure, text, 0);
        return text.toString();
    }

    private static void append(Throwable failure, StringBuilder text, int depth) {
        if (failure == null || depth > 20) {
            return;
        }
        text.append(failure.getClass().getName())
                .append(": ")
                .append(failure.getMessage())
                .append('\n');
        for (Throwable suppressed : failure.getSuppressed()) {
            append(suppressed, text, depth + 1);
        }
        append(failure.getCause(), text, depth + 1);
    }
}
