package org.quarkpress.testing.wprestparity;

import java.util.Collection;
import java.util.List;

/**
 * The known differences of the media parity run that a given run can show. A line whose scenario is skipped is left
 * out, since nothing can show it. A line whose reason says {@code (needs gd_webp)} or {@code (needs gd_avif)} applies
 * only when WordPress's GD writes that format: without it WordPress refuses those uploads too, so the line is left out
 * and counted (whether or not the run requires GD as the editor), and the run can say how many it could not check.
 * Left-out lines stay in the text as comments ({@code # left out of this run: ...}).
 */
public final class MediaKnownDifferences {

    private MediaKnownDifferences() {}

    /** The effective list as text, and how many lines were left out for a GD format WordPress's PHP lacks. */
    public record Effective(String text, int leftOutForFormats) {}

    /**
     * The lines of a known-differences file that a run with {@code skipped} scenarios and these GD formats can show.
     */
    public static Effective effective(List<String> lines, Collection<String> skipped, boolean gdWebp, boolean gdAvif) {
        StringBuilder kept = new StringBuilder();
        int leftOutForFormats = 0;
        for (String line : lines) {
            String text = line.strip();
            if (!text.isEmpty() && !text.startsWith("#")) {
                int space = text.indexOf(' ');
                String scenario = space < 0 ? text : text.substring(0, space);
                boolean missing = !skipped.contains(scenario) && needsMissingFormat(text, gdWebp, gdAvif);
                if (missing) {
                    leftOutForFormats++;
                }
                if (missing || skipped.contains(scenario)) {
                    kept.append("# left out of this run: ");
                }
            }
            kept.append(line).append('\n');
        }
        return new Effective(kept.toString(), leftOutForFormats);
    }

    /** Whether a line needs a GD format the server does not write. */
    private static boolean needsMissingFormat(String text, boolean gdWebp, boolean gdAvif) {
        return (!gdWebp && text.contains("(needs gd_webp)")) || (!gdAvif && text.contains("(needs gd_avif)"));
    }
}
