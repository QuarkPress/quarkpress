package org.quarkword.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.hibernate.Session;
import org.hibernate.jpa.HibernateHints;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteId;
import org.quarkword.persistence.SiteScopedNativeSql;
import org.quarkword.persistence.runtime.Dialects;
import org.quarkword.persistence.runtime.TenantIds;
import org.quarkword.posts.PostLookups;

/**
 * The front end's lookups on the current site's rows, read past capabilities. The recorded old slugs and old days
 * ({@code _wp_old_slug}, {@code _wp_old_date}) are read with each database's own JSON functions, as
 * {@link FeaturedImageReferences} reads {@code _thumbnail_id}: any value, read as text, equal to the slug. Dates are
 * compared in the site's time zone, in Java, as the date conditions of post queries are.
 */
@SiteScopedNativeSql
@ApplicationScoped
public class OldSlugLookups implements PostLookups {

    static final String OLD_SLUG = "_wp_old_slug";
    static final String OLD_DATE = "_wp_old_date";

    // the key is a constant of this class, never request input
    private static final String POSTGRESQL = "select id from qw_post where site_id = ?1 and type = ?2 and exists"
            + " (select 1 from jsonb_array_elements_text(case when jsonb_typeof(cast(meta as jsonb) -> '" + OLD_SLUG
            + "') = 'array' then cast(meta as jsonb) -> '" + OLD_SLUG + "' else cast('[]' as jsonb) end) as v(t)"
            + " where v.t = ?3) order by id";
    private static final String MYSQL = "select distinct p.id from qw_post p, json_table(p.meta, '$." + OLD_SLUG
            + "[*]' columns (t varchar(1024) path '$' null on error)) as v where p.site_id = ?1 and p.type = ?2"
            + " and v.t = ?3 order by p.id";

    /** Printable ASCII in the slug index's order (lower-case letters stand for both cases). */
    static final String ASCII_ORDER = " `^_-,;:!?.'\"()[]{}@*/\\&#%+<=>|~$0123456789abcdefghijklmnopqrstuvwxyz";

    /** The characters of the slug index's prefix key. */
    static final int INDEXED = 191;

    /** Rows fetched at a time by the guess. */
    private static final int FETCH_SIZE = 256;

    /** The escape character of the prefix pattern. */
    private static final char ESCAPE = '!';

    @Inject
    PostRepository posts;

    @Inject
    SiteContext site;

    @Override
    public Optional<PostId> firstBySlugPrefix(
            List<String> types, List<String> statuses, String prefix, DateParts date) {
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(statuses, "statuses");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(date, "date");
        if (types.isEmpty() || statuses.isEmpty()) {
            return Optional.empty();
        }
        SiteId siteId = site.siteId();
        ZoneId zone = site.zone();
        // the pattern narrows the rows; the exact prefix is checked here, as a database collation may fold case. The
        // rows are streamed and only the first so far is kept, in the slug index's order, then by id
        Object[] first = null;
        try (Stream<Object[]> rows = posts.getEntityManager()
                .createQuery(
                        "select p.id, p.slug, p.publishedAt, p.modifiedAt from PostEntity p where p.siteId = :site"
                                + " and p.type in :types and p.status in :statuses"
                                + " and p.slug like :pattern escape '" + ESCAPE + "'",
                        Object[].class)
                .setParameter("site", TenantIds.param(siteId))
                .setParameter("types", types)
                .setParameter("statuses", statuses)
                .setParameter("pattern", likePrefix(prefix))
                .setHint(HibernateHints.HINT_FETCH_SIZE, FETCH_SIZE)
                .getResultStream()) {
            for (Object[] row : (Iterable<Object[]>) rows::iterator) {
                String slug = (String) row[1];
                if (!slug.startsWith(prefix) || (first != null && !before(row, first))) {
                    continue;
                }
                LocalDate day = PostDates.localDate(Optional.ofNullable((Instant) row[2]), (Instant) row[3], zone)
                        .toLocalDate();
                if (matches(day, date)) {
                    first = new Object[] {row[0], slug};
                }
            }
        }
        return first == null ? Optional.empty() : Optional.of(PostId.of((Long) first[0]));
    }

    /** Whether the row {@code a} (id, slug, ...) comes before {@code b} in the slug index's order, then id order. */
    private static boolean before(Object[] a, Object[] b) {
        int bySlug = compareSlugs((String) a[1], (String) b[1]);
        return bySlug < 0 || (bySlug == 0 && (Long) a[0] < (Long) b[0]);
    }

    /**
     * The order of WordPress's slug index on MySQL ({@code post_name(191)} under {@code utf8mb4_unicode_520_ci}), as
     * mysql:9.5 reads it in an index range scan: only the first {@value #INDEXED} characters count; printable ASCII
     * weighs as {@link #ASCII_ORDER} lists it, letters case folded; any other character comes after those, by code
     * point. A shorter text that is a prefix of a longer one comes first.
     */
    static int compareSlugs(String a, String b) {
        int i = 0;
        int j = 0;
        for (int count = 0; count < INDEXED; count++) {
            boolean endA = i >= a.length();
            boolean endB = j >= b.length();
            if (endA || endB) {
                return endA == endB ? 0 : endA ? -1 : 1;
            }
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            int byWeight = Integer.compare(weight(ca), weight(cb));
            if (byWeight != 0) {
                return byWeight;
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return 0;
    }

    private static int weight(int c) {
        int folded = c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c;
        int at = folded < 0x80 ? ASCII_ORDER.indexOf(folded) : -1;
        return at >= 0 ? at : ASCII_ORDER.length() + folded;
    }

    @Override
    public Optional<PostId> firstByOldSlug(String type, String slug, DateParts date) {
        Objects.requireNonNull(date, "date");
        ZoneId zone = site.zone();
        return withOldSlug(type, slug)
                .filter(row -> matches(postDate(row, zone).toLocalDate(), date))
                .findFirst()
                .map(row -> PostId.of(row.id));
    }

    @Override
    public Optional<PostId> firstByOldDate(String type, String slug, DateParts date) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(date, "date");
        if (date.isEmpty()) {
            return Optional.empty();
        }
        return posts
                .find("siteId = ?1 and type = ?2 and slug = ?3 order by id", TenantIds.param(site.siteId()), type, slug)
                .stream()
                .filter(row -> oldDateMatches(row, date))
                .findFirst()
                .map(row -> PostId.of(row.id));
    }

    @Override
    public Optional<PostId> firstByOldSlugAndDate(String type, String slug, DateParts date) {
        Objects.requireNonNull(date, "date");
        if (date.isEmpty()) {
            return Optional.empty();
        }
        return withOldSlug(type, slug)
                .filter(row -> oldDateMatches(row, date))
                .findFirst()
                .map(row -> PostId.of(row.id));
    }

    /** The rows of {@code type} with {@code slug} among their old slugs, by ascending id. */
    private Stream<PostEntity> withOldSlug(String type, String slug) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(slug, "slug");
        SiteId siteId = site.siteId();
        Session session = posts.getSession();
        List<Long> ids = session.createNativeQuery(Dialects.postgresql(session) ? POSTGRESQL : MYSQL, Long.class)
                .setParameter(1, siteId.value())
                .setParameter(2, type)
                .setParameter(3, slug)
                .getResultList();
        return ids.stream().map(id -> posts.findPost(siteId, PostId.of(id))).flatMap(Optional::stream);
    }

    /** Whether one of the row's recorded old days matches every part of {@code date}. */
    private static boolean oldDateMatches(PostEntity row, DateParts date) {
        for (JsonNode value : MetaRows.values(row.meta, OLD_DATE)) {
            Optional<LocalDate> day = day(value.asText());
            if (day.isPresent() && matches(day.get(), date)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A recorded day: the leading numbers of {@code YYYY-MM-DD} (a time after the day is ignored); empty when the text
     * does not start with a valid date.
     */
    static Optional<LocalDate> day(String text) {
        int[] parts = new int[3];
        int at = 0;
        String value = text.trim();
        for (int part = 0; part < 3; part++) {
            int start = at;
            long number = 0;
            while (at < value.length() && value.charAt(at) >= '0' && value.charAt(at) <= '9' && at - start < 9) {
                number = number * 10 + (value.charAt(at) - '0');
                at++;
            }
            if (at == start) {
                return Optional.empty();
            }
            parts[part] = (int) number;
            if (part < 2) {
                if (at >= value.length() || value.charAt(at) != '-') {
                    return Optional.empty();
                }
                at++;
            }
        }
        try {
            return Optional.of(LocalDate.of(parts[0], parts[1], parts[2]));
        } catch (DateTimeException invalid) {
            return Optional.empty();
        }
    }

    private static boolean matches(LocalDate day, DateParts date) {
        return equal(date.year(), day.getYear())
                && equal(date.month(), day.getMonthValue())
                && equal(date.day(), day.getDayOfMonth());
    }

    private static boolean equal(OptionalInt part, int value) {
        return part.isEmpty() || part.getAsInt() == value;
    }

    private static LocalDateTime postDate(PostEntity row, ZoneId zone) {
        return PostDates.localDate(Optional.ofNullable(row.publishedAt), row.modifiedAt, zone);
    }

    /** {@code prefix} followed by any text, as a LIKE pattern escaped with {@link #ESCAPE}. */
    static String likePrefix(String prefix) {
        StringBuilder pattern = new StringBuilder(prefix.length() + 1);
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c == '%' || c == '_' || c == ESCAPE) {
                pattern.append(ESCAPE);
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }
}
