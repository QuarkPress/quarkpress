package org.quarkpress.testing.wporacle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deliberate differences from WordPress, one per line: {@code <case-id-or-glob> <aspect-or-glob> <audit-item>
 * <reason>}. Globs know {@code *} and {@code ?}. A case pattern of {@code none} documents a difference that no
 * oracle case can show. The list can only shrink: an entry whose comparisons all match WordPress (stale) or that
 * covers no comparison at all fails the run.
 */
public final class ParitySkipList {

    private static final String DOCUMENTATION_ONLY = "none";
    private static final Pattern AUDIT_ITEM = Pattern.compile("#\\d+|D\\d+");

    /** One skip-list line. */
    public static final class Entry {
        private final String casePattern;
        private final String aspectPattern;
        private final String auditItem;
        private final String reason;
        private final int line;
        private final Pattern caseRegex;
        private final Pattern aspectRegex;
        private int covered;
        private int differed;

        Entry(String casePattern, String aspectPattern, String auditItem, String reason, int line) {
            this.casePattern = casePattern;
            this.aspectPattern = aspectPattern;
            this.auditItem = auditItem;
            this.reason = reason;
            this.line = line;
            this.caseRegex = glob(casePattern);
            this.aspectRegex = glob(aspectPattern);
        }

        public String auditItem() {
            return auditItem;
        }

        public String reason() {
            return reason;
        }

        boolean documentationOnly() {
            return DOCUMENTATION_ONLY.equals(casePattern);
        }

        boolean matches(String caseId, String aspect) {
            return !documentationOnly()
                    && caseRegex.matcher(caseId).matches()
                    && aspectRegex.matcher(aspect).matches();
        }

        @Override
        public String toString() {
            return "line " + line + " (" + casePattern + " " + aspectPattern + " " + auditItem + ")";
        }
    }

    private final List<Entry> entries;
    private final String source;

    private ParitySkipList(List<Entry> entries, String source) {
        this.entries = List.copyOf(entries);
        this.source = source;
    }

    public static ParitySkipList load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }

    public static ParitySkipList empty() {
        return new ParitySkipList(List.of(), "(no skip list)");
    }

    static ParitySkipList parse(String text, String source) {
        List<Entry> entries = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+", 4);
            if (parts.length < 4) {
                throw new IllegalArgumentException(
                        source + ":" + (i + 1) + ": expected '<case> <aspect> <audit-item> <reason>'");
            }
            if (!AUDIT_ITEM.matcher(parts[2]).matches()) {
                throw new IllegalArgumentException(
                        source + ":" + (i + 1) + ": the audit item must look like #12 or D7, not " + parts[2]);
            }
            entries.add(new Entry(parts[0], parts[1], parts[2], parts[3], i + 1));
        }
        return new ParitySkipList(entries, source);
    }

    /** The first entry that covers this comparison. */
    public Optional<Entry> find(String caseId, String aspect) {
        return entries.stream().filter(e -> e.matches(caseId, aspect)).findFirst();
    }

    /** Records that {@code entry} covered one comparison, and whether QuarkPress differed there. */
    public void record(Entry entry, boolean differed) {
        entry.covered++;
        if (differed) {
            entry.differed++;
        }
    }

    /** Entries that covered nothing, or only comparisons that now match WordPress. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        for (Entry e : entries) {
            if (e.documentationOnly()) {
                continue;
            }
            if (e.covered == 0) {
                problems.add(source + " " + e + " matches no comparison; remove it");
            } else if (e.differed == 0) {
                problems.add(source + " " + e + " is stale: all " + e.covered
                        + " comparisons it covers match WordPress now; remove it");
            }
        }
        return problems;
    }

    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
