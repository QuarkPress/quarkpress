package org.quarkpress.testing.wpscenario;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Normalization shared by both sides' post records. */
public final class PostRecords {

    /** The meta keys a post record compares: those WordPress's post functions write themselves. */
    public static final List<String> META_KEYS = List.of(
            "_wp_desired_post_slug", "_wp_old_date", "_wp_old_slug", "_wp_trash_meta_status", "_wp_trash_meta_time");

    /** WordPress's text of a zero GMT date. */
    public static final String ZERO_DATE = "0000-00-00 00:00:00";

    /** The options whose value is a post id. */
    static final List<String> POST_OPTIONS = List.of("page_on_front", "page_for_posts");

    /** WordPress's {@code Y-m-d H:i:s}, read strictly: no February 30th, no hour 24. */
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private PostRecords() {}

    public static String format(LocalDateTime time) {
        return FORMAT.format(time);
    }

    /** A {@code Y-m-d H:i:s} text as a date and time; a date that does not exist is refused. */
    public static LocalDateTime parse(String text) {
        return LocalDateTime.parse(text, FORMAT);
    }

    /** A {@code Y-m-d H:i:s} UTC text as an instant. */
    public static Instant utc(String text) {
        return parse(text).toInstant(ZoneOffset.UTC);
    }

    /**
     * A slug whose id-shaped token ({@code <id>}, then any {@code -<digits>} and {@code __trashed} suffixes) names a
     * post: the post's own id as {@code #self} (WordPress's fallback for a post without title), another inserted post
     * as {@code #p<k>}; any other slug as it is. The two sides number posts differently.
     */
    public static String slug(String slug, long ownId, Map<Long, Integer> postIndex) {
        int end = idLength(slug);
        if (end == 0) {
            return slug;
        }
        long id = Long.parseLong(slug.substring(0, end));
        Integer index = postIndex.get(id);
        if (id == ownId) {
            return "#self" + slug.substring(end);
        }
        return index == null ? slug : "#p" + index + slug.substring(end);
    }

    /**
     * The number a slug reads as a post id (its id-shaped token, as {@link #slug} reads it), or -1 when it does not
     * read as one.
     */
    static long idNumber(String slug) {
        int end = idLength(slug);
        return end == 0 ? -1 : Long.parseLong(slug.substring(0, end));
    }

    /** The length of a slug's leading id when the slug is id-shaped, else 0. */
    private static int idLength(String slug) {
        int end = 0;
        while (end < slug.length() && isDigit(slug.charAt(end))) {
            end++;
        }
        return end == 0 || end > 18 || slug.charAt(0) == '0' || !suffixes(slug, end) ? 0 : end;
    }

    /** A page's path of slugs, each segment named as {@link #slug} names another post's slug. */
    public static String pageUri(String uri, Map<Long, Integer> postIndex) {
        StringBuilder shown = new StringBuilder();
        int from = 0;
        while (true) {
            int slash = uri.indexOf('/', from);
            String segment = slash < 0 ? uri.substring(from) : uri.substring(from, slash);
            shown.append(slug(segment, -1, postIndex));
            if (slash < 0) {
                return shown.toString();
            }
            shown.append('/');
            from = slash + 1;
        }
    }

    /**
     * Meta values as compared, joined with {@code |}: a trash time inside the run and an old date on the run's day as
     * {@code now}, slugs in the slug keys named as {@link #slug} names them.
     */
    public static String metaText(
            String key, List<String> values, long ownId, Map<Long, Integer> postIndex, RunWindow window) {
        List<String> shown = new ArrayList<>();
        for (String value : values) {
            shown.add(
                    switch (key) {
                        case "_wp_trash_meta_time" ->
                            digits(value) && value.length() < 19
                                    ? window.show(Instant.ofEpochSecond(Long.parseLong(value)), value)
                                    : value;
                        case "_wp_old_date" -> window.showLocalDay(value);
                        case "_wp_desired_post_slug", "_wp_old_slug" -> slug(value, ownId, postIndex);
                        default -> value;
                    });
        }
        return String.join("|", shown);
    }

    /**
     * The posts of a query without order, as a sorted set: indexed posts by index, then any other by id. Both sides
     * record such a query this way.
     */
    public static List<String> sortedRefs(List<String> refs) {
        List<String> sorted = new ArrayList<>(refs);
        sorted.sort(
                Comparator.comparing((String ref) -> !ref.startsWith("p")).thenComparingLong(ref -> refNumber(ref)));
        return sorted;
    }

    private static long refNumber(String ref) {
        String number = ref.startsWith("p") ? ref.substring(1) : ref.startsWith("#?") ? ref.substring(2) : "";
        return digits(number) && number.length() < 19 ? Long.parseLong(number) : Long.MAX_VALUE;
    }

    private static boolean suffixes(String slug, int from) {
        int i = from;
        while (i < slug.length()) {
            if (slug.startsWith("__trashed", i)) {
                i += "__trashed".length();
            } else if (slug.charAt(i) == '-') {
                int j = i + 1;
                while (j < slug.length() && isDigit(slug.charAt(j))) {
                    j++;
                }
                if (j == i + 1) {
                    return false;
                }
                i = j;
            } else {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** A plain-permalink guid on the home URL with its id named by insertion index; any other guid as it is. */
    public static String guid(String guid, String home, Map<Long, Integer> postIndex) {
        if (!guid.startsWith(home + "/?")) {
            return guid;
        }
        int equals = guid.lastIndexOf('=');
        String id = guid.substring(equals + 1);
        Integer index = digits(id) ? postIndex.get(Long.parseLong(id)) : null;
        String tail = index == null ? id : "p" + index;
        return "home" + guid.substring(home.length(), equals + 1) + tail;
    }

    /** A GMT date as compared: {@code zero} for WordPress's zero date, {@code now} within the run, else as it is. */
    public static String gmtText(String gmt, RunWindow window) {
        return gmt.equals(ZERO_DATE) ? "zero" : window.show(utc(gmt), gmt);
    }

    /**
     * A local date's distance from its GMT counterpart, in minutes ({@code zero} when the GMT date is WordPress's zero
     * date): run-independent, so a zone slip on a stamp of the run still shows.
     */
    public static String offsetMinutes(String local, String gmt) {
        if (gmt.equals(ZERO_DATE)) {
            return "zero";
        }
        return Long.toString(Duration.between(parse(gmt), parse(local)).toMinutes());
    }

    public static String postRef(long id, Map<Long, Integer> postIndex) {
        if (id == 0) {
            return "none";
        }
        Integer index = postIndex.get(id);
        return index == null ? "#?" + id : "p" + index;
    }

    /**
     * An option value as compared: {@code page_on_front} and {@code page_for_posts} hold a post id, named by insertion
     * index as {@link #postRef} names it; other options, and values that are not a number, as they are.
     */
    public static String optionText(String name, String text, Map<Long, Integer> postIndex) {
        if (!POST_OPTIONS.contains(name) || !digits(text) || text.length() > 18) {
            return text;
        }
        return postRef(Long.parseLong(text), postIndex);
    }

    public static String userRef(long id, Map<Long, Integer> userIndex, long installerId) {
        if (id == 0) {
            return "none";
        }
        if (id == installerId) {
            return "admin";
        }
        Integer index = userIndex.get(id);
        return index == null ? "#?" + id : "u" + index;
    }

    /**
     * Whether post records compare guids: only when every post is inserted while the main site's permalinks are plain,
     * which a scenario sets with {@code permalink_structure} {@code ""}. WordPress fixes a guid at insertion from the
     * post's link, which is pretty for a published post under WordPress's network default
     * ({@code /blog/%year%/%monthnum%/%day%/%postname%/}); QuarkPress always stores the plain form.
     */
    public static boolean includeGuid(Scenario scenario) {
        boolean plain = false;
        for (ScenarioOp op : scenario.ops()) {
            if (op instanceof ScenarioOp.SiteOption option && option.name().equals("permalink_structure")) {
                plain = option.value().isEmpty();
            } else if (op instanceof ScenarioOp.InsertPost && !plain) {
                return false;
            }
        }
        return true;
    }

    public static Record record() {
        return new Record();
    }

    /**
     * Builds a post record in the compared field order: {@code field(name, value)} for each column, then
     * {@code meta(key, values)} for each meta key, which the record lists after the fields as {@code meta:<key>}.
     */
    public static final class Record {
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final Map<String, String> meta = new TreeMap<>();

        private Record() {}

        public Record field(String name, String value) {
            fields.put(name, value);
            return this;
        }

        public Record meta(String key, String values) {
            meta.put(key, values);
            return this;
        }

        public ObservedPost build() {
            Map<String, String> all = new LinkedHashMap<>(fields);
            meta.forEach((key, value) -> all.put("meta:" + key, value));
            return new ObservedPost(all);
        }
    }

    private static boolean digits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
