package org.quarkpress.testing.wpscenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How term observations are compared: ids named by creation order on the site during the scenario ({@code t<k>}) or
 * as a taxonomy's default term ({@code default-<taxonomy>}), {@code none} for 0, and a slug that is the term's own id
 * as {@code #self}. The two sides number terms differently.
 */
public final class TermRecords {

    private TermRecords() {}

    public static String termRef(long id, List<Long> created, Map<Long, String> defaults) {
        if (id == 0) {
            return "none";
        }
        String fallback = defaults.get(id);
        if (fallback != null) {
            return fallback;
        }
        int index = created.indexOf(id);
        return index < 0 ? "#?" + id : "t" + (index + 1);
    }

    /**
     * The slug as compared: its own id as {@code #self}, keeping numeric suffixes ({@code <id>(-<digits>)*}, the
     * forms an id slug takes when made unique); any other slug as it is.
     */
    public static String slug(String slug, long ownId) {
        String own = Long.toString(ownId);
        if (!slug.startsWith(own) || !numericSuffixes(slug, own.length())) {
            return slug;
        }
        return "#self" + slug.substring(own.length());
    }

    /** Whether the text from {@code start} on is empty or {@code -<digits>} repeated. */
    private static boolean numericSuffixes(String slug, int start) {
        int i = start;
        while (i < slug.length()) {
            if (slug.charAt(i) != '-' || i + 1 >= slug.length() || !isDigit(slug.charAt(i + 1))) {
                return false;
            }
            i++;
            while (i < slug.length() && isDigit(slug.charAt(i))) {
                i++;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** A term record (keys id, taxonomy, name, slug, description, parent, count) as compared. */
    public static ObservedTerm record(Map<String, Object> raw, List<Long> created, Map<Long, String> defaults) {
        long id = ((Number) raw.get("id")).longValue();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("taxonomy", (String) raw.get("taxonomy"));
        fields.put("name", (String) raw.get("name"));
        fields.put("slug", slug((String) raw.get("slug"), id));
        fields.put("description", (String) raw.get("description"));
        fields.put("parent", termRef(((Number) raw.get("parent")).longValue(), created, defaults));
        fields.put("count", Long.toString(((Number) raw.get("count")).longValue()));
        return new ObservedTerm(fields);
    }

    /**
     * Terms of a query without order, as a sorted set: default terms, then created ones by index, then others; an entry
     * {@code ref=count} sorts by its reference.
     */
    public static List<String> sortedRefs(List<String> refs) {
        List<String> sorted = new ArrayList<>(refs);
        sorted.sort(Comparator.comparingInt((String ref) -> rank(plain(ref)))
                .thenComparingInt(ref -> ScenarioRules.termIndex(plain(ref)))
                .thenComparing(Comparator.naturalOrder()));
        return sorted;
    }

    private static String plain(String ref) {
        int equals = ref.indexOf('=');
        return equals < 0 ? ref : ref.substring(0, equals);
    }

    private static int rank(String ref) {
        if (ref.startsWith("default-")) {
            return 0;
        }
        return ScenarioRules.termIndex(ref) > 0 ? 1 : 2;
    }
}
