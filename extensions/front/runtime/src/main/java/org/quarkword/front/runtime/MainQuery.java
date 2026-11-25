package org.quarkword.front.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import org.quarkword.core.PostId;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.SiteOptions;
import org.quarkword.core.TaxonomyDefinition;
import org.quarkword.core.UserId;
import org.quarkword.front.QueriedObject;
import org.quarkword.front.QueryVars;
import org.quarkword.front.Viewer;
import org.quarkword.posts.DateBound;
import org.quarkword.posts.DateColumn;
import org.quarkword.posts.DateCondition;
import org.quarkword.posts.OrderKey;
import org.quarkword.posts.PostOrder;
import org.quarkword.posts.PostQuery;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.TaxClause;
import org.quarkword.posts.runtime.Slugs;
import org.quarkword.security.UserProfile;
import org.quarkword.security.UserProfiles;

/**
 * The main query WordPress's {@code WP_Query} builds from a request's parsed variables and flags: the post types
 * (the given {@code post_type}; the searchable types for a search; the searchable types a queried taxonomy is attached
 * to; else attachment, page or post by the flags), the date conditions ({@code m}, {@code year} with {@code monthnum},
 * {@code day} and the time, {@code w} under the site's {@code start_of_week}), the slug and id ({@code name}, the
 * resolved {@code pagename}, {@code attachment}, {@code attachment_id}, {@code p}, and {@code page_id}, which replaces
 * the conditions before it), the authors ({@code author} lists, {@code author_name} by nicename), the order
 * ({@code orderby} and {@code order}, a search ranked by relevance first unless ordered otherwise, an exact search or
 * a feed), the search, the term filters, the page size from {@code posts_per_page}, the page from {@code paged}, and
 * sticky posts first on the first page of a home list. No status is given: a list shows published posts and the
 * private ones the viewer may read, and a query for one post checks it as WordPress checks a single post.
 *
 * <p>Date units that name one stretch of time (a year, a year and month, a day, and a day with its hour, minute and
 * second in that order) are compared as one range of dates. Others (a month, a day or a time without the units above
 * it, such as {@code monthnum=5} or {@code hour=8}) are compared unit by unit, as WordPress compares {@code
 * MONTH(post_date) = 5}: May of every year, eight o'clock of every day. An hour and a second without the minute add no
 * time condition, as in WordPress.
 */
@ApplicationScoped
public class MainQuery {

    /** A date condition no post meets: before the year 0. */
    public static final DateCondition NOTHING = DateCondition.before(DateColumn.DATE, DateBound.year(0), false);

    private static final int MAX_YEAR = 9999;
    private static final int MAX_UNIT = 99;
    private static final long DEFAULT_PER_PAGE = 10;

    @Inject
    UserProfiles profiles;

    @Inject
    PagePaths pages;

    /**
     * The main query of a parsed request; empty when the parse already found the request not to exist (its {@code
     * error} is 404).
     */
    public Optional<PostQuery> build(
            FlagParser.Parsed parsed, SiteOptions options, RequestParser.Registries registries, Viewer viewer) {
        Objects.requireNonNull(parsed, "parsed");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(viewer, "viewer");
        if (parsed.flags().is404()) {
            return Optional.empty();
        }
        QueryVars vars = parsed.normalized();
        PostQuery.Builder query = PostQuery.builder();
        List<String> types = types(parsed, registries);
        boolean postsPage = parsed.flags().postsPage();
        long pageId = number(vars, "page_id");
        if (pageId > 0 && !postsPage) {
            // the page's id replaces every condition WordPress builds before it: dates, week, slug and id
            query.id(PostId.of(pageId));
        } else {
            types = slugAndId(parsed, options, vars, query, types);
        }
        query.types(types.toArray(String[]::new));
        authors(vars, query);
        String search = search(vars);
        if (!search.isEmpty()) {
            query.search(search);
        }
        boolean exact = truthy(vars, "exact");
        query.exactSearch(exact);
        query.sentence(truthy(vars, "sentence"));
        order(vars, parsed, !search.isEmpty() && !exact)
                .forEach(order -> query.orderBy(order.key(), order.ascending()));
        query.taxQuery(parsed.taxQuery());
        query.perPage(clamp(options.number("posts_per_page", DEFAULT_PER_PAGE)));
        long paged = number(vars, "paged");
        query.page((int) Math.max(1, Math.min(paged, Integer.MAX_VALUE)));
        query.stickyFirst(parsed.flags().home() && paged <= 1);
        return Optional.of(query.build());
    }

    /** The date, week, slug and id conditions; the types, which a page path resolving to an attachment changes. */
    private List<String> slugAndId(
            FlagParser.Parsed parsed,
            SiteOptions options,
            QueryVars vars,
            PostQuery.Builder query,
            List<String> types) {
        dates(vars, query);
        long week = number(vars, "w");
        if (week != 0) {
            // (int) get_option('start_of_week'): an empty, non-numeric or missing option is 0
            query.week(
                    (int) Math.min(week, Integer.MAX_VALUE),
                    clamp(FlagParser.phpInt(options.string("start_of_week", ""))));
        }
        List<String> selected = types;
        String name = vars.get("name").orElse("");
        String pagename = vars.get("pagename").orElse("");
        String attachment = vars.get("attachment").orElse("");
        if (!name.isEmpty()) {
            query.name(name);
        } else if (!pagename.isEmpty()) {
            if (!parsed.flags().postsPage()) {
                Optional<PostSnapshot> page = pageAt(parsed, pagename, vars);
                if (page.isPresent()) {
                    query.id(page.get().id());
                    if (page.get().type().equals("attachment")) {
                        selected = List.of("attachment");
                    }
                } else {
                    query.date(NOTHING);
                }
            }
        } else if (!attachment.isEmpty()) {
            query.name(basename(attachment));
        }
        long attachmentId = number(vars, "attachment_id");
        long p = attachmentId != 0 ? attachmentId : number(vars, "p");
        if (p > 0) {
            query.id(PostId.of(p));
        }
        return selected;
    }

    private static int clamp(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(value, Integer.MAX_VALUE));
    }

    // ---- types ----------------------------------------------------------------------------------------------------

    private static List<String> types(FlagParser.Parsed parsed, RequestParser.Registries registries) {
        String postType = parsed.normalized().get("post_type").orElse("");
        if (!postType.isEmpty()) {
            return postType.equals("any") ? searchable(registries) : List.of(postType);
        }
        if (parsed.flags().search()) {
            return searchable(registries);
        }
        if (parsed.flags().tax()) {
            Set<String> queried = new LinkedHashSet<>();
            for (TaxClause clause : parsed.taxQuery().clauses()) {
                taxonomies(clause, queried);
            }
            Set<String> attached = new TreeSet<>();
            for (PostTypeDefinition type : registries.postTypes()) {
                if (!type.excludeFromSearch() && attachedToAny(type.key(), queried, registries)) {
                    attached.add(type.key());
                }
            }
            return attached.isEmpty() ? searchable(registries) : List.copyOf(attached);
        }
        if (parsed.flags().attachment()) {
            return List.of("attachment");
        }
        if (parsed.flags().page()) {
            return List.of("page");
        }
        return List.of("post");
    }

    /** The types searches cover: every registered type not left out of searches, in registration order. */
    private static List<String> searchable(RequestParser.Registries registries) {
        return registries.postTypes().stream()
                .filter(type -> !type.excludeFromSearch())
                .map(PostTypeDefinition::key)
                .toList();
    }

    private static void taxonomies(TaxClause clause, Set<String> into) {
        if (!clause.positive()) {
            return;
        }
        switch (clause) {
            case TaxClause.Terms terms -> into.add(terms.taxonomy());
            case TaxClause.Slugs slugs -> into.add(slugs.taxonomy());
            case TaxClause.Group group -> group.clauses().forEach(inner -> taxonomies(inner, into));
            default -> {}
        }
    }

    private static boolean attachedToAny(String type, Set<String> taxonomies, RequestParser.Registries registries) {
        for (TaxonomyDefinition taxonomy : registries.taxonomies()) {
            if (!taxonomies.contains(taxonomy.key())) {
                continue;
            }
            for (String objectType : taxonomy.objectTypes()) {
                if (objectType.equals(type) || objectType.startsWith(type + ":")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- slugs and ids --------------------------------------------------------------------------------------------

    /**
     * The post a {@code pagename} path names: the one the parse resolved, else, for a request naming other types,
     * the first hierarchical type's post at the path.
     */
    private Optional<PostSnapshot> pageAt(FlagParser.Parsed parsed, String pagename, QueryVars vars) {
        Optional<QueriedObject> resolved = parsed.queriedFromPath();
        if (resolved.isPresent()) {
            return resolved.get() instanceof QueriedObject.Post post ? Optional.of(post.post()) : Optional.empty();
        }
        String postType = vars.get("post_type").orElse("");
        if (postType.isEmpty() || postType.equals("page")) {
            return Optional.empty();
        }
        return pages.find(pagename, List.of(postType));
    }

    /** WordPress's {@code wp_basename}: the last path segment, trailing slashes first removed. */
    static String basename(String path) {
        int end = path.length();
        while (end > 0 && (path.charAt(end - 1) == '/' || path.charAt(end - 1) == '\\')) {
            end--;
        }
        String trimmed = path.substring(0, end);
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return trimmed.substring(slash + 1);
    }

    // ---- dates ----------------------------------------------------------------------------------------------------

    /**
     * The date conditions of {@code m} and of the date and time units: one range when the units name one stretch of
     * time, else the units compared one by one; {@link #NOTHING} for a unit beyond any date's.
     */
    private static void dates(QueryVars vars, PostQuery.Builder query) {
        String m = vars.get("m").orElse("");
        if (!m.isEmpty() && !m.equals("0")) {
            query.date(fromM(m));
        }
        long year = number(vars, "year");
        long month = number(vars, "monthnum");
        long day = number(vars, "day");
        Optional<Long> hour = unit(vars, "hour");
        Optional<Long> minute = unit(vars, "minute");
        Optional<Long> second = unit(vars, "second");
        if (hour.isPresent() && second.isPresent() && minute.isEmpty()) {
            // WordPress builds no time condition for an hour and a second without the minute
            hour = Optional.empty();
            second = Optional.empty();
        }
        boolean time = hour.isPresent() || minute.isPresent() || second.isPresent();
        boolean date = year != 0 || month != 0 || day != 0;
        if (!date && !time) {
            return;
        }
        if (year > MAX_YEAR
                || month > MAX_UNIT
                || day > MAX_UNIT
                || hour.orElse(0L) > MAX_UNIT
                || minute.orElse(0L) > MAX_UNIT
                || second.orElse(0L) > MAX_UNIT) {
            query.date(NOTHING);
            return;
        }
        boolean rangeOfDate = year != 0 && (month != 0 || day == 0);
        boolean rangeOfTime = !time
                || (year != 0
                        && month != 0
                        && day != 0
                        && hour.isPresent()
                        && (second.isEmpty() || minute.isPresent()));
        if (!rangeOfDate || !rangeOfTime) {
            query.units(new PostQuery.DateUnits(
                    optional(year), optional(month), optional(day), time(hour), time(minute), time(second)));
            return;
        }
        DateBound bound =
                new DateBound((int) year, optional(month), optional(day), time(hour), time(minute), time(second));
        query.date(DateCondition.between(DateColumn.DATE, bound, bound, true));
    }

    /** {@code m}: its first four digits the year, then two digits each for the month, day, hour, minute and second. */
    private static DateCondition fromM(String m) {
        int year = Integer.parseInt(m.substring(0, Math.min(4, m.length())));
        OptionalInt[] units = new OptionalInt[5];
        for (int i = 0; i < units.length; i++) {
            int start = 4 + 2 * i;
            units[i] = m.length() > start + 1
                    ? OptionalInt.of(Integer.parseInt(m.substring(start, start + 2)))
                    : OptionalInt.empty();
        }
        DateBound bound = new DateBound(year, units[0], units[1], units[2], units[3], units[4]);
        return DateCondition.between(DateColumn.DATE, bound, bound, true);
    }

    /** A time unit that is set: the variable is present and not empty ({@code 0} counts). */
    private static Optional<Long> unit(QueryVars vars, String name) {
        String value = vars.get(name).orElse("");
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(parse(value));
    }

    /** A month or day: 0 is none. */
    private static OptionalInt optional(long value) {
        return value == 0 ? OptionalInt.empty() : OptionalInt.of((int) value);
    }

    /** A time unit: 0 is a value. */
    private static OptionalInt time(Optional<Long> value) {
        return value.map(unit -> OptionalInt.of(unit.intValue())).orElse(OptionalInt.empty());
    }

    // ---- authors --------------------------------------------------------------------------------------------------

    /**
     * {@code author}: the ids split on commas and spaces, positive ones listed, negative ones (and 0) excluded, the
     * exclusion winning; {@code author_name}: the user by nicename, or nothing for an unknown nicename.
     */
    private void authors(QueryVars vars, PostQuery.Builder query) {
        String author = vars.get("author").orElse("");
        if (!author.isEmpty() && !zero(author)) {
            Set<UserId> in = new LinkedHashSet<>();
            Set<UserId> notIn = new LinkedHashSet<>();
            boolean excludesNone = false;
            for (String piece : split(author, true)) {
                long id = FlagParser.phpInt(piece);
                if (id > 0) {
                    in.add(UserId.of(id));
                } else if (id < 0 && id != Long.MIN_VALUE) {
                    notIn.add(UserId.of(-id));
                } else {
                    excludesNone = true;
                }
            }
            if (!notIn.isEmpty()) {
                query.authorNotIn(notIn.toArray(UserId[]::new));
            } else if (!excludesNone && !in.isEmpty()) {
                query.authorIn(in.toArray(UserId[]::new));
            }
        }
        authorByName(vars).ifPresentOrElse(user -> query.authorIn(user.id()), () -> {
            if (!vars.get("author_name").orElse("").isEmpty()) {
                query.date(NOTHING);
            }
        });
    }

    /** Whether a normalised {@code author} is a number equal to 0 ({@code 0}, {@code 00}, {@code -0}). */
    private static boolean zero(String author) {
        int start = author.startsWith("-") ? 1 : 0;
        if (start == author.length()) {
            return false;
        }
        for (int i = start; i < author.length(); i++) {
            if (author.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /** The user {@code author_name} names by nicename: its last path segment (the one before a trailing slash). */
    Optional<UserProfile> authorByName(QueryVars vars) {
        String name = vars.get("author_name").orElse("");
        if (name.isEmpty()) {
            return Optional.empty();
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            String last = name.substring(slash + 1);
            if (last.isEmpty()) {
                String before = name.substring(0, slash);
                last = before.substring(before.lastIndexOf('/') + 1);
            }
            name = last;
        }
        String nicename = Slugs.sanitizeTitleForQuery(name);
        return nicename.isEmpty() ? Optional.empty() : profiles.findByNicename(nicename);
    }

    // ---- search and order -----------------------------------------------------------------------------------------

    /** {@code s}, decoded once more when it came from the path, as WordPress decodes the main query's search. */
    private static String search(QueryVars vars) {
        String s = vars.get("s").orElse("");
        if (s.isEmpty()) {
            return s;
        }
        boolean fromQueryString = vars.fromQueryString().contains("s") && !s.equals("0");
        return fromQueryString ? s : RewriteRules.phpUrldecode(s);
    }

    /**
     * The order: {@code orderby}'s space-separated keys, each in {@code order}'s direction, unknown ones dropped,
     * the date when none is left; a search ranked by relevance first when {@code ranked} and the request names no
     * order (and is not a feed) or names {@code relevance}.
     */
    private static List<PostOrder> order(QueryVars vars, FlagParser.Parsed parsed, boolean ranked) {
        boolean ascending =
                vars.get("order").orElse("").toUpperCase(Locale.ROOT).equals("ASC");
        String orderby = vars.get("orderby").orElse("");
        List<PostOrder> order = new ArrayList<>();
        if (orderby.equals("post__in") || orderby.equals("post_name__in") || orderby.equals("post_parent__in")) {
            // the list key without its list: WordPress orders by the date with no direction, which is ascending
            order.add(new PostOrder(OrderKey.DATE, true));
        } else {
            for (String key : split(orderby, false)) {
                OrderKey mapped = key(key);
                if (mapped != null) {
                    order.add(new PostOrder(mapped, ascending));
                }
            }
            if (order.isEmpty()) {
                order.add(new PostOrder(OrderKey.DATE, ascending));
            }
        }
        if (ranked && ((orderby.isEmpty() && !parsed.flags().feed()) || orderby.equals("relevance"))) {
            order.add(0, new PostOrder(OrderKey.RELEVANCE, ascending));
        }
        return order;
    }

    /** WordPress's orderby key; null for a key QuarkWord drops (unknown ones, and those it cannot order by). */
    private static OrderKey key(String key) {
        return switch (key) {
            case "ID" -> OrderKey.ID;
            case "author", "post_author" -> OrderKey.AUTHOR;
            case "title", "post_title" -> OrderKey.TITLE;
            case "name", "post_name" -> OrderKey.NAME;
            case "type", "post_type" -> OrderKey.TYPE;
            case "date", "post_date" -> OrderKey.DATE;
            case "modified", "post_modified" -> OrderKey.MODIFIED;
            case "parent", "post_parent" -> OrderKey.PARENT;
            case "menu_order" -> OrderKey.MENU_ORDER;
            case "none" -> OrderKey.NONE;
            default -> null;
        };
    }

    /**
     * The non-empty pieces of {@code text} between separators: commas and PHP's whitespace ({@code [,\\s]+}) when
     * {@code commas}, else spaces.
     */
    private static List<String> split(String text, boolean commas) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            boolean separator = i == text.length() || separator(text.charAt(i), commas);
            if (separator) {
                if (i > start) {
                    pieces.add(text.substring(start, i));
                }
                start = i + 1;
            }
        }
        return pieces;
    }

    private static boolean separator(char c, boolean commas) {
        if (!commas) {
            return c == ' ';
        }
        return c == ',' || c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f';
    }

    // ---- values ---------------------------------------------------------------------------------------------------

    /** A normalised number variable; 0 when absent or empty. */
    static long number(QueryVars vars, String name) {
        return vars.get(name).map(MainQuery::parse).orElse(0L);
    }

    private static long parse(String value) {
        return value.isEmpty() ? 0 : FlagParser.phpInt(value);
    }

    /** PHP's {@code ! empty()} of a variable: present, and neither empty nor {@code 0}. */
    private static boolean truthy(QueryVars vars, String name) {
        String value = vars.get(name).orElse("");
        return !value.isEmpty() && !value.equals("0");
    }
}
