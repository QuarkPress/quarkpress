package org.quarkpress.testing.wporacle;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Collects the comparisons of one parity test: each is one aspect (a function or view) of one case, WordPress's
 * value against QuarkPress's. Skip-listed comparisons do not count. {@link #assertParity} fails on any
 * difference, on stale or unused skip entries, and when fewer comparisons ran than the test expects, so an empty
 * corpus can never pass. Differing generated cases are written to a JSON file with their (shrunk) input so they
 * can be copied into the module's hand-written cases.
 */
public final class ParityRun {

    /** One aspect of one case where QuarkPress and WordPress disagree. */
    public record Mismatch(String caseId, String aspect, String wordpress, String quarkpress) {}

    private static final int SHOWN = 40;
    private static final int MAX_VALUE_TEXT = 400;

    private final String library;
    private final ParitySkipList skips;
    private final Path failuresFile;
    private final List<Mismatch> mismatches = new ArrayList<>();
    private final Map<String, String> generatedInputs = new LinkedHashMap<>();
    private final Map<String, String> shrunkInputs = new HashMap<>();
    private int compared;

    public ParityRun(String library, ParitySkipList skips, Path failuresFile) {
        this.library = Objects.requireNonNull(library, "library");
        this.skips = Objects.requireNonNull(skips, "skips");
        this.failuresFile = Objects.requireNonNull(failuresFile, "failuresFile");
    }

    /** Compares one aspect of one case; true when the values are equal or the comparison is skip-listed. */
    public boolean compare(String caseId, String aspect, Object wordpress, Object quarkpress) {
        boolean same = Objects.equals(wordpress, quarkpress);
        Optional<ParitySkipList.Entry> skip = skips.find(caseId, aspect);
        if (skip.isPresent()) {
            skips.record(skip.get(), !same);
            return true;
        }
        compared++;
        if (!same) {
            mismatches.add(new Mismatch(caseId, aspect, render(wordpress), render(quarkpress)));
        }
        return same;
    }

    /** Marks a case as generated; its input is reported when the case differs. */
    public void generated(String caseId, String input) {
        generatedInputs.put(caseId, input);
    }

    /** A smaller input that still shows the difference of a generated case. */
    public void shrunk(String caseId, String input) {
        shrunkInputs.put(caseId, input);
    }

    /** Generated cases that differ from WordPress, in comparison order. */
    public List<String> failedGeneratedCases() {
        return mismatches.stream()
                .map(Mismatch::caseId)
                .filter(generatedInputs::containsKey)
                .distinct()
                .toList();
    }

    public int comparisons() {
        return compared;
    }

    public List<Mismatch> mismatches() {
        return List.copyOf(mismatches);
    }

    public void assertParity(int minimumComparisons) throws IOException {
        writeFailuresFile();
        List<String> problems = new ArrayList<>();
        if (compared < minimumComparisons) {
            problems.add("only " + compared + " comparisons ran; at least " + minimumComparisons
                    + " were expected (is a corpus missing?)");
        }
        problems.addAll(skips.problems());
        if (!mismatches.isEmpty()) {
            StringBuilder text = new StringBuilder(mismatches.size() + " differences:");
            for (Mismatch m : mismatches.subList(0, Math.min(SHOWN, mismatches.size()))) {
                text.append("\n    ")
                        .append(m.caseId())
                        .append(" [")
                        .append(m.aspect())
                        .append("]\n      wordpress:  ")
                        .append(m.wordpress())
                        .append("\n      quarkpress: ")
                        .append(m.quarkpress());
            }
            if (mismatches.size() > SHOWN) {
                text.append("\n    and ").append(mismatches.size() - SHOWN).append(" more");
            }
            if (!failedGeneratedCases().isEmpty()) {
                text.append("\n    generated inputs: ").append(failuresFile);
            }
            problems.add(text.toString());
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(library + " differs from WordPress:\n- " + String.join("\n- ", problems));
        }
    }

    private void writeFailuresFile() throws IOException {
        List<String> failed = failedGeneratedCases();
        if (failed.isEmpty()) {
            Files.deleteIfExists(failuresFile);
            return;
        }
        ArrayNode out = OracleJson.MAPPER.createArrayNode();
        for (String id : failed) {
            ObjectNode entry = out.addObject();
            entry.put("id", id);
            entry.put("input", generatedInputs.get(id));
            entry.put("shrunk", shrunkInputs.getOrDefault(id, generatedInputs.get(id)));
            ArrayNode differences = entry.putArray("differences");
            for (Mismatch m : mismatches) {
                if (m.caseId().equals(id)) {
                    differences
                            .addObject()
                            .put("aspect", m.aspect())
                            .put("wordpress", m.wordpress())
                            .put("quarkpress", m.quarkpress());
                }
            }
        }
        Path parent = failuresFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        OracleJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(failuresFile.toFile(), out);
    }

    private static String render(Object value) {
        String text = String.valueOf(value);
        return text.length() <= MAX_VALUE_TEXT
                ? text
                : text.substring(0, MAX_VALUE_TEXT) + "... (" + text.length() + " chars)";
    }
}
