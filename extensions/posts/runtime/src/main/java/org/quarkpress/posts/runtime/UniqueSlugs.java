package org.quarkpress.posts.runtime;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.quarkpress.core.PostId;

/**
 * WordPress's unique-slug rules for one site. Draft, pending and auto-draft posts keep their slug as it is.
 * Attachments must differ from every post of the site; posts of a hierarchical type from posts of their type and
 * attachments under the same parent, and they never take an all-digit or {@code page<digits>} slug; posts of a flat
 * type from posts of their type, and a {@code post} never takes a number its site's permalink structure would read as
 * a year, month or day. The feed names and {@code embed} are never taken. A taken slug gets {@code -2}, {@code -3},
 * and so on, each time from the original cut to fit 200 bytes. A stored row whose slug is empty or {@code 0}
 * never makes a slug taken (a page's own {@code 0} is still all digits and gets a suffix).
 */
public final class UniqueSlugs {

    /** What trashing appends to a slug. */
    public static final String TRASHED_SUFFIX = "__trashed";

    private static final Set<String> RESERVED = Set.of("feed", "rdf", "rss", "rss2", "atom", "embed");

    private static final int TRASHED_BASE_BYTES = 191;

    /**
     * Answers whether a slug is taken in the site whose posts are being saved. Posts of every status and type count,
     * trashed, auto-draft, revision and menu-item rows included, and the slug must match the stored one exactly.
     */
    public interface Lookup {

        /** A post other than {@code exclude}, of any type, has {@code slug}. */
        boolean anyType(String slug, Optional<PostId> exclude);

        /** A post of {@code type} other than {@code exclude} has {@code slug}. */
        boolean sameType(String slug, String type, Optional<PostId> exclude);

        /**
         * A post of {@code type}, or an attachment, other than {@code exclude}, whose parent is {@code parent} (empty:
         * top level), has {@code slug}.
         */
        boolean sameTypeOrAttachmentUnder(String slug, String type, Optional<PostId> parent, Optional<PostId> exclude);
    }

    private UniqueSlugs() {}

    /** Whether a slug of this status and type is kept as it is, without uniqueness and without slug filters. */
    public static boolean exempt(String status, String type) {
        return PostRules.NO_SLUG_STATUSES.contains(status)
                || (status.equals("inherit") && type.equals("revision"))
                || type.equals("user_request");
    }

    /** The slug the post gets; call only for statuses and types that are not {@link #exempt}. */
    public static String unique(
            String slug,
            Optional<PostId> post,
            Optional<String> storedSlug,
            String type,
            Optional<PostId> parent,
            boolean hierarchical,
            String permalinkStructure,
            Lookup lookup) {
        Predicate<String> taken;
        boolean conflict;
        if (type.equals("attachment")) {
            taken = candidate -> lookup.anyType(candidate, post);
            conflict = exists(slug, taken) || RESERVED.contains(slug);
        } else if (hierarchical) {
            if (type.equals("nav_menu_item")) {
                return slug;
            }
            taken = candidate -> lookup.sameTypeOrAttachmentUnder(candidate, type, parent, post);
            conflict = exists(slug, taken) || RESERVED.contains(slug) || paginationLike(slug);
        } else {
            taken = candidate -> lookup.sameType(candidate, type, post);
            conflict = exists(slug, taken)
                    || RESERVED.contains(slug)
                    || dateArchiveLike(slug, type, storedSlug, permalinkStructure);
        }
        if (!conflict) {
            return slug;
        }
        for (int suffix = 2; ; suffix++) {
            String digits = Integer.toString(suffix);
            String candidate = Slugs.truncate(slug, Slugs.MAX_BYTES - digits.length() - 1) + "-" + digits;
            if (!exists(candidate, taken)) {
                return candidate;
            }
        }
    }

    /** Whether the slug ends with {@link #TRASHED_SUFFIX}. */
    public static boolean isTrashedSlug(String slug) {
        return slug.endsWith(TRASHED_SUFFIX);
    }

    /** The slug a post gets when trashed: its slug cut to 191 bytes plus {@code __trashed}, unless it ends so. */
    public static String withTrashedSuffix(String storedSlug) {
        return isTrashedSlug(storedSlug) ? storedSlug : Slugs.truncate(storedSlug, TRASHED_BASE_BYTES) + TRASHED_SUFFIX;
    }

    private static boolean exists(String slug, Predicate<String> taken) {
        return !slug.isEmpty() && !slug.equals("0") && taken.test(slug);
    }

    /** All ASCII digits, optionally after {@code page}: what a paginated URL would read. */
    private static boolean paginationLike(String slug) {
        String digits = slug.startsWith("page") ? slug.substring(4) : slug;
        return isDigits(digits);
    }

    private static boolean dateArchiveLike(
            String slug, String type, Optional<String> storedSlug, String permalinkStructure) {
        if (!type.equals("post") || storedSlug.map(slug::equals).orElse(false) || !isDigits(slug)) {
            return false;
        }
        BigInteger value = new BigInteger(slug);
        if (value.signum() == 0) {
            return false;
        }
        List<String> segments = segments(permalinkStructure);
        int index = segments.indexOf("%postname%");
        if (index < 0) {
            return false;
        }
        if (index == 0) {
            return true;
        }
        String before = segments.get(index - 1);
        return (before.equals("%year%") && value.compareTo(BigInteger.valueOf(13)) < 0)
                || (before.equals("%monthnum%") && value.compareTo(BigInteger.valueOf(32)) < 0);
    }

    /** The structure cut at each slash, empty and {@code 0} segments left out. */
    private static List<String> segments(String structure) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= structure.length(); i++) {
            if (i == structure.length() || structure.charAt(i) == '/') {
                if (i > start && !(i - start == 1 && structure.charAt(start) == '0')) {
                    segments.add(structure.substring(start, i));
                }
                start = i + 1;
            }
        }
        return segments;
    }

    private static boolean isDigits(String text) {
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
