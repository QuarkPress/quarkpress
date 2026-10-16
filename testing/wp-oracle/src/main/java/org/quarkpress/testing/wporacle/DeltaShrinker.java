package org.quarkpress.testing.wporacle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Shrinks a generated input that differs from WordPress. Each round removes one chunk at every position (at most
 * {@value #MAX_CANDIDATES} candidates), asks {@code stillFails} about all of them at once, so a round costs one
 * oracle run, and keeps the first candidate that still differs. When none does, the chunk size halves; the search
 * ends at single characters or after {@code maxRounds} rounds.
 */
public final class DeltaShrinker {

    static final int MAX_CANDIDATES = 64;

    private DeltaShrinker() {}

    public static String shrink(String input, Function<List<String>, List<Boolean>> stillFails, int maxRounds) {
        String current = input;
        int chunk = Math.max(1, current.length() / 2);
        for (int round = 0; round < maxRounds && !current.isEmpty(); round++) {
            List<String> candidates = new ArrayList<>();
            for (int start = 0; start < current.length() && candidates.size() < MAX_CANDIDATES; start += chunk) {
                int end = Math.min(current.length(), start + chunk);
                candidates.add(current.substring(0, start) + current.substring(end));
            }
            int hit = stillFails.apply(candidates).indexOf(Boolean.TRUE);
            if (hit >= 0) {
                current = candidates.get(hit);
                chunk = Math.min(chunk, Math.max(1, current.length() / 2));
            } else if (chunk == 1) {
                break;
            } else {
                chunk = Math.max(1, chunk / 2);
            }
        }
        return current;
    }
}
