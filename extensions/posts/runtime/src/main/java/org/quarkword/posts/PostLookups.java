package org.quarkword.posts;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.quarkword.core.PostId;

/**
 * The lookups WordPress's front end makes when a request found nothing: a post whose slug starts with what the
 * request named, and a post that once had the requested slug or date. They read the current site's rows as stored,
 * whoever asks: no capability is checked, and the old slug and old date lookups check no status either. Slugs are
 * compared exactly, on the stored (lower-case) form.
 */
public interface PostLookups {

    /**
     * The parts of a date a request named, each compared on its own with the post's date in the site's time zone
     * (a year alone matches every post of that year). An empty part, and a part of 0, does not narrow anything.
     */
    record DateParts(OptionalInt year, OptionalInt month, OptionalInt day) {

        /** No part: nothing narrowed. */
        public static final DateParts NONE =
                new DateParts(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty());

        public DateParts {
            Objects.requireNonNull(year, "year");
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(day, "day");
            year = positive(year);
            month = positive(month);
            day = positive(day);
        }

        /** The parts {@code year}, {@code month} and {@code day}, each left out when it is 0 or less. */
        public static DateParts of(long year, long month, long day) {
            return new DateParts(part(year), part(month), part(day));
        }

        /** Whether no part is given. */
        public boolean isEmpty() {
            return year.isEmpty() && month.isEmpty() && day.isEmpty();
        }

        private static OptionalInt part(long value) {
            return value > 0 && value <= Integer.MAX_VALUE ? OptionalInt.of((int) value) : OptionalInt.empty();
        }

        private static OptionalInt positive(OptionalInt value) {
            return value.isPresent() && value.getAsInt() <= 0 ? OptionalInt.empty() : value;
        }
    }

    /**
     * The post of one of {@code types} with one of {@code statuses} whose slug starts with {@code prefix} (taken
     * literally: {@code %} and {@code _} are no wildcards), dated as {@code date} says: the first in the order of
     * WordPress's slug index on MySQL (its collation over the first 191 characters: {@code _} before {@code -} before
     * {@code %} before digits before letters, case folded), then by id, as WordPress's database answers the same
     * question through that index, whatever the database here.
     */
    Optional<PostId> firstBySlugPrefix(List<String> types, List<String> statuses, String prefix, DateParts date);

    /**
     * The lowest-id post of {@code type}, of any status, that once had the slug {@code slug} and whose date matches
     * {@code date}.
     */
    Optional<PostId> firstByOldSlug(String type, String slug, DateParts date);

    /**
     * The lowest-id post of {@code type}, of any status, whose current slug is {@code slug} and that was once dated on
     * a day matching {@code date}; empty when {@code date} names no part.
     */
    Optional<PostId> firstByOldDate(String type, String slug, DateParts date);

    /**
     * The lowest-id post of {@code type}, of any status, that once had the slug {@code slug} and was once dated on a
     * day matching {@code date}; empty when {@code date} names no part.
     */
    Optional<PostId> firstByOldSlugAndDate(String type, String slug, DateParts date);
}
