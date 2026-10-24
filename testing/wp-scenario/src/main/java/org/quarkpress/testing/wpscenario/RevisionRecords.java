package org.quarkpress.testing.wpscenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How revision observations are compared: revision rows named by creation order on the site during the scenario
 * ({@code r<k>}), posts by insertion order, users by creation order. The two sides number rows differently.
 */
public final class RevisionRecords {

    private RevisionRecords() {}

    /**
     * {@code r<k>} for a revision row created during the scenario, {@code p<k>} for an inserted post, else
     * {@code #?<id>}.
     */
    static String ref(long id, List<Long> created, Map<Long, Integer> postIndex) {
        int index = created.indexOf(id);
        if (index >= 0) {
            return "r" + (index + 1);
        }
        return postIndex.containsKey(id) ? PostRecords.postRef(id, postIndex) : "#?" + id;
    }

    /** The values of the revisioned meta key joined with {@code |}; {@code (none)} when there are none. */
    static String footnotes(List<String> values) {
        return values.isEmpty() ? "(none)" : String.join("|", values);
    }

    /**
     * A driver revision record, normalized. The raw keys are {@code id}, {@code parent}, {@code author},
     * {@code autosave} (a boolean), {@code title}, {@code content}, {@code excerpt}, {@code footnotes} (a list of
     * texts) and {@code date_gmt}.
     */
    @SuppressWarnings("unchecked")
    static ObservedRevision record(
            Map<String, Object> raw,
            List<Long> created,
            Map<Long, Integer> postIndex,
            Map<Long, Integer> userIndex,
            long installerId,
            RunWindow window) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("parent", PostRecords.postRef(((Number) raw.get("parent")).longValue(), postIndex));
        fields.put("author", PostRecords.userRef(((Number) raw.get("author")).longValue(), userIndex, installerId));
        fields.put("autosave", Boolean.TRUE.equals(raw.get("autosave")) ? "1" : "0");
        fields.put("title", (String) raw.get("title"));
        fields.put("content", (String) raw.get("content"));
        fields.put("excerpt", (String) raw.get("excerpt"));
        fields.put("footnotes", footnotes((List<String>) raw.get("footnotes")));
        fields.put("date_gmt", PostRecords.gmtText((String) raw.get("date_gmt"), window));
        return new ObservedRevision(fields);
    }
}
