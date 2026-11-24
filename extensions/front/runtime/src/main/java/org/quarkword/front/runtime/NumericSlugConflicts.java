package org.quarkword.front.runtime;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.quarkword.front.QueryVars;
import org.quarkword.posts.PostSnapshot;

/**
 * WordPress's resolution of a post whose slug looks like a date archive segment: when the structure puts
 * {@code %postname%} first, or right after {@code %year%} or {@code %monthnum%}, a request that parsed as a year,
 * month or day archive may really name such a post. The post is looked up by path; when it exists (and its date agrees
 * with the other date variables), the request becomes the post's, with the following date variable as its
 * {@code <!--nextpage-->} page when the post has that many pages; otherwise the archive stands.
 */
public final class NumericSlugConflicts {

    private static final String NEXTPAGE = "<!--nextpage-->";

    private NumericSlugConflicts() {}

    /**
     * {@code vars} with a slug/date clash resolved, dates read in {@code zone}: {@code postByPath} finds a post of type
     * {@code post} by its path, {@code pageCount} counts a post's {@code <!--nextpage-->} pages.
     */
    public static QueryVars resolve(
            QueryVars vars,
            Structure structure,
            Function<String, Optional<PostSnapshot>> postByPath,
            Function<PostSnapshot, Integer> pageCount,
            ZoneId zone) {
        Objects.requireNonNull(vars, "vars");
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(postByPath, "postByPath");
        Objects.requireNonNull(pageCount, "pageCount");
        Objects.requireNonNull(zone, "zone");
        if (!vars.has("year") && !vars.has("monthnum") && !vars.has("day")) {
            return vars;
        }
        List<String> segments = segments(structure.value());
        int postname = segments.indexOf("%postname%");
        if (postname < 0) {
            return vars;
        }
        String compare;
        if (postname == 0 && (vars.has("year") || vars.has("monthnum"))) {
            compare = "year";
        } else if (postname > 0
                && segments.get(postname - 1).equals("%year%")
                && (vars.has("monthnum") || vars.has("day"))) {
            compare = "monthnum";
        } else if (postname > 0 && segments.get(postname - 1).equals("%monthnum%") && vars.has("day")) {
            compare = "day";
        } else {
            return vars;
        }
        Optional<String> value = vars.get(compare);
        if (value.isEmpty() || value.get().isEmpty()) {
            return vars;
        }
        Optional<PostSnapshot> found = postByPath.apply(value.get());
        if (found.isEmpty()) {
            return vars;
        }
        PostSnapshot post = found.get();
        LocalDateTime date = post.localDate(zone);
        // the post's date is compared only with the date variables that are set
        if (!compare.equals("year") && vars.has("year") && !sameNumber(vars.get("year"), date.getYear())) {
            return vars;
        }
        if (compare.equals("day") && vars.has("monthnum") && !sameNumber(vars.get("monthnum"), date.getMonthValue())) {
            return vars;
        }
        Optional<String> candidate = switch (compare) {
            case "year" -> vars.get("monthnum");
            case "monthnum" -> vars.get("day");
            default -> Optional.empty();
        };
        // the candidate counts as PHP's integer cast counts it: 0 (or a non-number) is no page at all
        long page = candidate.map(NumericSlugConflicts::asNumber).orElse(0L);
        int pages = pageCount.apply(post);
        if (pages <= 1 && page != 0) {
            return vars;
        }
        if (pages > 1 && page > pages) {
            return vars;
        }
        return vars.without("year", "monthnum", "day")
                .with("page", Long.toString(page))
                .with("name", post.slug());
    }

    /** The number of {@code <!--nextpage-->} pages of {@code content}: the separators plus one. */
    public static int pages(String content) {
        int count = 1;
        int from = 0;
        while (true) {
            int at = content.indexOf(NEXTPAGE, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + NEXTPAGE.length();
        }
    }

    private static boolean sameNumber(Optional<String> value, int expected) {
        return value.isPresent() && asNumber(value.get()) == expected;
    }

    /** PHP's integer cast of a query value: the leading digits, 0 for none. */
    private static long asNumber(String value) {
        long number = 0;
        for (int i = 0; i < value.length() && i < 18; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            number = number * 10 + (c - '0');
        }
        return number;
    }

    private static List<String> segments(String structure) {
        List<String> segments = new ArrayList<>();
        int from = 0;
        while (from <= structure.length()) {
            int slash = structure.indexOf('/', from);
            int end = slash < 0 ? structure.length() : slash;
            if (end > from) {
                segments.add(structure.substring(from, end));
            }
            from = end + 1;
        }
        return segments;
    }
}
