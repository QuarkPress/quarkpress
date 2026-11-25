package org.quarkword.front.runtime;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.SiteOptions;
import org.quarkword.core.TaxonomyDefinition;
import org.quarkword.core.WordPressKeys;
import org.quarkword.front.FrontFlags;
import org.quarkword.front.QueriedObject;
import org.quarkword.front.QueryVars;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.TaxClause;
import org.quarkword.posts.TaxQuery;
import org.quarkword.posts.runtime.Slugs;

/**
 * WordPress's {@code parse_query}: the public query variables of a request are normalised the way PHP reads them
 * (integer casts of strings, absolute values, digits only, trimmed slugs) and turned into the conditional flags, the
 * page a {@code pagename} path resolves to, and the taxonomy query. The singular ladder (attachment, name, id, page)
 * skips the archive branch (search, dates, taxonomies, author, post type archive); feeds, embeds, trackbacks, paging
 * and previews follow; a static front page claims a bare request and moves {@code paged} into {@code page}; the
 * {@code pagename} lookup finds pages and attachments by path (a post of the same path wins when the structure starts
 * with the post name), recognises the posts page and the privacy page; a request marked not found loses every other
 * flag but {@code embed}.
 */
public final class FlagParser {

    /** What the parser made of a request's variables. */
    public record Parsed(
            QueryVars normalized, FrontFlags flags, Optional<QueriedObject> queriedFromPath, TaxQuery taxQuery) {
        public Parsed {
            Objects.requireNonNull(normalized, "normalized");
            Objects.requireNonNull(flags, "flags");
            Objects.requireNonNull(queriedFromPath, "queriedFromPath");
            Objects.requireNonNull(taxQuery, "taxQuery");
        }
    }

    private static final List<String> PAGE_TYPES = List.of("page", "attachment");
    private static final List<String> POST_TYPES = List.of("post", "attachment");
    private static final int MAX_SEARCH_BYTES = 1600;
    private static final int MAX_CHECKDATE_YEAR = 32767;
    private static final Set<String> FRONT_PAGE_KEYS = Set.of("preview", "page", "paged", "cpage");
    private static final List<String> ABSOLUTE_INTEGERS =
            List.of("page_id", "year", "monthnum", "day", "w", "paged", "attachment_id");
    private static final List<String> TIME_UNITS = List.of("hour", "minute", "second", "menu_order");
    private static final List<String> TRIMMED = List.of("pagename", "name", "title");
    private static final String CATEGORY = "category";
    private static final String TAG = "post_tag";
    private static final String COMMENTS_PREFIX = "comments-";

    private FlagParser() {}

    /** The flags, normalised variables, queried page and tax query of {@code raw}, the request's public variables. */
    public static Parsed parse(
            QueryVars raw,
            SiteOptions options,
            RequestParser.Registries registries,
            PagePaths pages,
            Structure structure) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(structure, "structure");
        State state = new State(normalise(raw));

        if (truthy(state.vars, "robots")) {
            state.flags = state.flags.withRobots(true);
        } else if (truthy(state.vars, "favicon")) {
            state.flags = state.flags.withFavicon(true);
        } else if (truthy(state.vars, "sitemap")) {
            state.flags = state.flags.withSitemap(true);
        }

        if (set(state.vars, "attachment") || number(state.vars, "attachment_id") != 0) {
            state.flags = state.flags.withSingle(true).withAttachment(true);
        } else if (set(state.vars, "name")) {
            state.flags = state.flags.withSingle(true);
        } else if (number(state.vars, "p") != 0) {
            state.flags = state.flags.withSingle(true);
        } else if (set(state.vars, "pagename") || number(state.vars, "page_id") != 0) {
            state.flags = state.flags.withPage(true);
        } else {
            archive(state, raw, registries);
        }
        boolean singular = singular(state.flags);
        state.flags = state.flags.withSingular(singular);

        feedEmbedPagingPreview(state, singular);

        boolean home = !(singular
                || state.flags.archive()
                || state.flags.search()
                || state.flags.feed()
                || state.flags.trackback()
                || state.flags.robots()
                || state.flags.favicon()
                || state.flags.sitemap());
        state.flags = state.flags.withHome(home);
        String showOnFront = options.string("show_on_front", "posts");
        long pageOnFront = options.number("page_on_front", 0);
        if (home && showOnFront.equals("page") && pageOnFront != 0 && bareRequest(raw)) {
            state.flags = state.flags.withPage(true).withHome(false);
            state.vars = state.vars.with("page_id", Long.toString(pageOnFront));
            if (number(state.vars, "paged") != 0) {
                state.vars = state.vars
                        .with("page", state.vars.get("paged").orElseThrow())
                        .without("paged");
            }
        }

        Optional<QueriedObject> queried = resolvePath(state, pages, structure);
        long pageForPosts = options.number("page_for_posts", 0);
        long pageId = number(state.vars, "page_id");
        if (showOnFront.equals("page")
                && ((queried.isPresent() && queried.get().id() == pageForPosts)
                        || (pageId != 0 && pageId == pageForPosts))) {
            state.flags = state.flags.withPage(false).withHome(true).withPostsPage(true);
            if (!truthy(state.vars, "withcomments")) {
                state.flags = state.flags.withCommentFeed(false);
            }
            queried = queried.map(object ->
                    object instanceof QueriedObject.Post post ? new QueriedObject.PostsPage(post.post()) : object);
        }
        long privacyPage = options.number("wp_page_for_privacy_policy", 0);
        if (privacyPage != 0 && ((queried.isPresent() && queried.get().id() == privacyPage) || pageId == privacyPage)) {
            state.flags = state.flags.withPrivacyPolicy(true);
        }
        state.flags = state.flags.withSingular(singular(state.flags));

        state.vars
                .get("post_type")
                .ifPresent(type -> state.vars = state.vars.with("post_type", WordPressKeys.sanitize(type)));
        state.vars
                .get("post_status")
                .ifPresent(status -> state.vars = state.vars.with("post_status", keep(status, false)));
        if (error404(state.vars)) {
            state.flags = FrontFlags.notFound();
        }
        boolean embed = set(state.vars, "embed") && (state.flags.singular() || state.flags.is404());
        state.flags = state.flags.withEmbed(embed);
        return new Parsed(state.vars, state.flags, queried, state.taxQuery);
    }

    /** The mutable working state of one parse. */
    private static final class State {
        QueryVars vars;
        FrontFlags flags = FrontFlags.none();
        TaxQuery taxQuery = TaxQuery.NONE;

        State(QueryVars vars) {
            this.vars = vars;
        }
    }

    // ---- normalisation -----------------------------------------------------------------------------------------

    private static QueryVars normalise(QueryVars raw) {
        QueryVars vars = raw;
        Optional<String> p = vars.get("p");
        if (p.isPresent()) {
            long value = phpInt(p.get());
            if (value < 0) {
                vars = vars.with("p", "0").with("error", "404");
            } else {
                vars = vars.with("p", Long.toString(value));
            }
        }
        for (String name : ABSOLUTE_INTEGERS) {
            if (vars.has(name)) {
                vars = vars.with(name, Long.toString(absInt(vars.get(name).orElseThrow())));
            }
        }
        if (vars.has("m")) {
            vars = vars.with("m", digits(vars.get("m").orElseThrow()));
        }
        for (String name : List.of("cat", "author")) {
            if (vars.has(name)) {
                vars = vars.with(name, keep(vars.get(name).orElseThrow(), true));
            }
        }
        for (String name : TRIMMED) {
            if (vars.has(name)) {
                vars = vars.with(name, phpTrim(vars.get(name).orElseThrow()));
            }
        }
        for (String name : TIME_UNITS) {
            if (vars.has(name)) {
                String value = vars.get(name).orElseThrow();
                vars = vars.with(name, value.isEmpty() ? "" : Long.toString(absInt(value)));
            }
        }
        Optional<String> search = vars.get("s");
        if (search.isPresent() && search.get().getBytes(StandardCharsets.UTF_8).length > MAX_SEARCH_BYTES) {
            vars = vars.with("s", "");
        }
        Optional<String> page = vars.get("page");
        if (page.isPresent() && !page.get().isEmpty()) {
            vars = vars.with("page", Long.toString(absInt(trimSlashes(page.get()))));
        }
        if (set(vars, "subpost")) {
            vars = vars.with("attachment", vars.get("subpost").orElseThrow());
        }
        if (set(vars, "subpost_id")) {
            vars = vars.with(
                    "attachment_id", Long.toString(absInt(vars.get("subpost_id").orElseThrow())));
        }
        return vars;
    }

    // ---- the archive branch ----------------------------------------------------------------------------------------

    private static void archive(State state, QueryVars raw, RequestParser.Registries registries) {
        if (raw.has("s")) {
            state.flags = state.flags.withSearch(true);
        }
        dates(state);
        taxQuery(state, registries);
        for (TaxClause clause : state.taxQuery.clauses()) {
            if (clause instanceof TaxClause.Terms terms && terms.operator() == TaxClause.Operator.NOT_IN) {
                continue;
            }
            String taxonomy =
                    clause instanceof TaxClause.Terms terms ? terms.taxonomy() : ((TaxClause.Slugs) clause).taxonomy();
            state.flags = switch (taxonomy) {
                case CATEGORY -> state.flags.withCategory(true);
                case TAG -> state.flags.withTag(true);
                default -> state.flags.withTax(true);
            };
        }
        Optional<String> author = state.vars.get("author");
        if ((author.isPresent() && !phpEmpty(author.get()) && !isZero(author.get()))
                || set(state.vars, "author_name")) {
            state.flags = state.flags.withAuthor(true);
        }
        Optional<String> postType = state.vars.get("post_type").filter(type -> !type.isEmpty());
        if (postType.isPresent()) {
            boolean archive = registries.postTypes().stream()
                    .filter(type -> type.key().equals(postType.get()))
                    .anyMatch(PostTypeDefinition::hasArchive);
            if (archive) {
                state.flags = state.flags.withPostTypeArchive(true);
            }
        }
        FrontFlags flags = state.flags;
        state.flags = flags.withArchive(flags.postTypeArchive()
                || flags.date()
                || flags.author()
                || flags.category()
                || flags.tag()
                || flags.tax());
    }

    private static void dates(State state) {
        if (set(state.vars, "hour") || set(state.vars, "minute") || set(state.vars, "second")) {
            state.flags = state.flags.withTime(true).withDate(true);
        }
        long day = number(state.vars, "day");
        long month = number(state.vars, "monthnum");
        long year = number(state.vars, "year");
        if (day != 0 && !state.flags.date()) {
            if (month != 0 && year != 0 && !validDate(year, month, day)) {
                state.vars = state.vars.with("error", "404");
            } else {
                state.flags = state.flags.withDay(true).withDate(true);
            }
        }
        if (month != 0 && !state.flags.date()) {
            if (month > 12) {
                state.vars = state.vars.with("error", "404");
            } else {
                state.flags = state.flags.withMonth(true).withDate(true);
            }
        }
        if (year != 0 && !state.flags.date()) {
            state.flags = state.flags.withYear(true).withDate(true);
        }
        Optional<String> m = state.vars.get("m").filter(value -> !phpEmpty(value));
        if (m.isPresent()) {
            state.flags = state.flags.withDate(true);
            int length = m.get().length();
            if (length > 9) {
                state.flags = state.flags.withTime(true);
            } else if (length > 7) {
                state.flags = state.flags.withDay(true);
            } else if (length > 5) {
                state.flags = state.flags.withMonth(true);
            } else {
                state.flags = state.flags.withYear(true);
            }
        }
        if (number(state.vars, "w") != 0) {
            state.flags = state.flags.withDate(true);
        }
    }

    /** PHP's {@code checkdate}: a real calendar day of a year between 1 and 32767. */
    private static boolean validDate(long year, long month, long day) {
        if (year < 1 || year > MAX_CHECKDATE_YEAR || month < 1 || month > 12 || day < 1 || day > 31) {
            return false;
        }
        return YearMonth.of((int) year, (int) month).isValidDay((int) day);
    }

    // ---- the tax query ---------------------------------------------------------------------------------------------

    private static void taxQuery(State state, RequestParser.Registries registries) {
        List<TaxClause> clauses = new ArrayList<>();
        Optional<String> taxonomy = state.vars.get("taxonomy").filter(value -> !phpEmpty(value));
        Optional<String> term = state.vars.get("term").filter(value -> !phpEmpty(value));
        if (taxonomy.isPresent() && term.isPresent()) {
            clauses.add(new TaxClause.Slugs(taxonomy.get(), List.of(lookupSlug(term.get()))));
        }
        for (TaxonomyDefinition definition : registries.taxonomies()) {
            if (definition.key().equals(TAG) || definition.queryVar().isEmpty()) {
                continue;
            }
            String queryVar = definition.queryVar().get();
            Optional<String> given = state.vars.get(queryVar).filter(value -> !phpEmpty(value));
            if (given.isEmpty()) {
                continue;
            }
            String value = given.get();
            boolean hierarchical = definition
                    .rewrite()
                    .map(TaxonomyDefinition.Rewrite::hierarchical)
                    .orElse(false);
            if (hierarchical && value.indexOf('/') >= 0) {
                // wp_basename: trailing slashes dropped, then the last segment
                String trimmed = trimTrailingSlashes(value);
                value = trimmed.substring(trimmed.lastIndexOf('/') + 1);
                state.vars = state.vars.with(queryVar, value);
            }
            List<String> pieces = new ArrayList<>();
            for (String piece : value.split(",", -1)) {
                pieces.add(phpTrim(piece));
            }
            pieces.sort(FlagParser::phpCompare);
            String joined = String.join(",", pieces);
            if (joined.indexOf('+') >= 0) {
                for (String piece : splitRuns(joined, c -> c == '+')) {
                    clauses.add(new TaxClause.Slugs(definition.key(), List.of(lookupSlug(piece))));
                }
            } else {
                clauses.add(new TaxClause.Slugs(
                        definition.key(),
                        pieces.stream().map(FlagParser::lookupSlug).toList()));
            }
        }
        Optional<String> cat = state.vars.get("cat").filter(value -> !phpEmpty(value));
        if (cat.isPresent()) {
            List<Long> ids = new ArrayList<>();
            for (String piece : splitRuns(cat.get(), c -> c == ',' || isPhpSpace(c))) {
                ids.add(phpInt(piece));
            }
            ids.sort(Long::compare);
            state.vars = state.vars.with(
                    "cat", String.join(",", ids.stream().map(String::valueOf).toList()));
            List<Long> in = ids.stream().filter(id -> id > 0).toList();
            List<Long> notIn = ids.stream().filter(id -> id < 0).map(id -> -id).toList();
            if (!in.isEmpty()) {
                clauses.add(new TaxClause.Terms(CATEGORY, in, true, TaxClause.Operator.IN));
            }
            if (!notIn.isEmpty()) {
                clauses.add(new TaxClause.Terms(CATEGORY, notIn, true, TaxClause.Operator.NOT_IN));
            }
        }
        Optional<String> tag = state.vars.get("tag").filter(value -> !value.isEmpty());
        if (tag.isPresent()) {
            String value = tag.get();
            if (value.indexOf(',') >= 0) {
                clauses.add(new TaxClause.Slugs(TAG, tagSlugs(splitRuns(value, c -> c == ',' || isTagSpace(c)))));
            } else if (value.indexOf('+') >= 0 || hasTagSpace(value) || cat.isPresent()) {
                for (String slug : tagSlugs(splitRuns(value, c -> c == '+' || isTagSpace(c)))) {
                    clauses.add(new TaxClause.Slugs(TAG, List.of(slug)));
                }
            } else {
                List<String> slug = tagSlugs(List.of(value));
                state.vars = state.vars.with("tag", slug.get(0));
                clauses.add(new TaxClause.Slugs(TAG, slug));
            }
        }
        state.taxQuery = clauses.isEmpty() ? TaxQuery.NONE : new TaxQuery(TaxClause.Relation.AND, clauses);
    }

    /**
     * The tag pieces as WordPress sanitises them when it reads the variable (a term slug field: the title sanitiser
     * in its save context, accents folded, then the query one), without repeats.
     */
    private static List<String> tagSlugs(List<String> pieces) {
        Set<String> slugs = new LinkedHashSet<>();
        for (String piece : pieces) {
            slugs.add(lookupSlug(Slugs.sanitizeTitleForQuery(Slugs.sanitizeTitle(piece))));
        }
        return List.copyOf(slugs);
    }

    /** The slug the term lookup compares against: WordPress sanitises a slug clause's terms as titles. */
    private static String lookupSlug(String piece) {
        return Slugs.sanitizeTitle(piece);
    }

    // ---- feeds, embeds, trackbacks, paging, previews -------------------------------------------------------------

    private static void feedEmbedPagingPreview(State state, boolean singular) {
        boolean feed = set(state.vars, "feed");
        state.flags = state.flags
                .withFeed(feed)
                .withTrackback(set(state.vars, "tb"))
                .withPaged(number(state.vars, "paged") > 1)
                .withPreview(set(state.vars, "preview"));
        String feedValue = state.vars.get("feed").orElse("");
        if (feedValue.contains(COMMENTS_PREFIX)) {
            state.vars = state.vars
                    .with("feed", feedValue.replace(COMMENTS_PREFIX, ""))
                    .with("withcomments", "1");
        }
        boolean withComments = set(state.vars, "withcomments");
        boolean withoutComments = set(state.vars, "withoutcomments");
        state.flags = state.flags.withCommentFeed(feed && (withComments || (!withoutComments && singular)));
    }

    /** Whether the original variables are nothing but what a static front page request may carry. */
    private static boolean bareRequest(QueryVars raw) {
        for (var entry : raw.asMap().entrySet()) {
            String name = entry.getKey();
            if (name.equals("embed")
                    || (name.equals("pagename") && entry.getValue().isEmpty())) {
                continue;
            }
            if (!FRONT_PAGE_KEYS.contains(name)) {
                return false;
            }
        }
        return true;
    }

    // ---- the pagename path -----------------------------------------------------------------------------------------

    private static Optional<QueriedObject> resolvePath(State state, PagePaths pages, Structure structure) {
        Optional<String> pagename = state.vars.get("pagename").filter(value -> !value.isEmpty());
        if (pagename.isEmpty()) {
            return Optional.empty();
        }
        Optional<PostSnapshot> found = pages.find(pagename.get(), PAGE_TYPES);
        if (found.isPresent() && found.get().type().equals("attachment") && structure.postnameFirst()) {
            // the second lookup is for posts, but WordPress's path lookup always includes attachments, so the
            // attachment itself is found again when no post shares the path: the request is single either way
            Optional<PostSnapshot> post = pages.find(pagename.get(), POST_TYPES);
            if (post.isPresent()) {
                state.flags = state.flags.withPage(false).withSingle(true);
                return Optional.of(new QueriedObject.Post(post.get()));
            }
        }
        return found.map(QueriedObject.Post::new);
    }

    // ---- helpers
    // -----------------------------------------------------------------------------------------------------

    private static boolean singular(FrontFlags flags) {
        return flags.single() || flags.page() || flags.attachment();
    }

    /** Whether the variable is present with a non-empty value. */
    private static boolean set(QueryVars vars, String name) {
        return vars.get(name).filter(value -> !value.isEmpty()).isPresent();
    }

    /** Whether the variable is present with a value PHP's {@code empty} does not reject (neither empty nor "0"). */
    private static boolean truthy(QueryVars vars, String name) {
        return vars.get(name).filter(value -> !phpEmpty(value)).isPresent();
    }

    /** Whether the value equals the number 0 as PHP compares a string with {@code "0"}: numeric and zero. */
    private static boolean isZero(String value) {
        return isNumeric(value) && phpDouble(value) == 0.0;
    }

    /** PHP's {@code empty} of a present value: empty or {@code "0"}. */
    private static boolean phpEmpty(String value) {
        return value.isEmpty() || value.equals("0");
    }

    /** The variable's integer value, 0 when absent. */
    private static long number(QueryVars vars, String name) {
        return vars.get(name).map(FlagParser::phpInt).orElse(0L);
    }

    /** Whether {@code error} equals 404 as PHP compares a string with a number. */
    private static boolean error404(QueryVars vars) {
        Optional<String> error = vars.get("error");
        return error.isPresent() && isNumeric(error.get()) && phpDouble(error.get()) == 404.0;
    }

    /**
     * PHP's integer cast of a string: leading whitespace, a sign, digits, an optional fraction and exponent are read;
     * anything else stops the number (none gives 0); a value beyond the range saturates, an infinite one is 0.
     */
    static long phpInt(String value) {
        int end = numericPrefixEnd(value);
        if (end < 0) {
            return 0;
        }
        String numeric = value.substring(skipWhitespace(value), end);
        if (numeric.indexOf('.') < 0 && numeric.indexOf('e') < 0 && numeric.indexOf('E') < 0) {
            String digits = numeric.startsWith("+") ? numeric.substring(1) : numeric;
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException overflow) {
                // PHP goes through the double value: beyond its range the number is infinite and casts to 0
                if (Double.isInfinite(Double.parseDouble(digits))) {
                    return 0;
                }
                return digits.startsWith("-") ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
        }
        double number = Double.parseDouble(numeric);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            return 0;
        }
        return (long) number;
    }

    /** WordPress's {@code absint}: the absolute value of the integer cast, saturating. */
    static long absInt(String value) {
        long number = phpInt(value);
        return number == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(number);
    }

    private static double phpDouble(String value) {
        int end = numericPrefixEnd(value);
        return end < 0 ? 0 : Double.parseDouble(value.substring(skipWhitespace(value), end));
    }

    /** Whether the whole value is a PHP numeric string (whitespace allowed around the number). */
    static boolean isNumeric(String value) {
        int end = numericPrefixEnd(value);
        if (end < 0) {
            return false;
        }
        for (int i = end; i < value.length(); i++) {
            if (!isPhpWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int skipWhitespace(String value) {
        int i = 0;
        while (i < value.length() && isPhpWhitespace(value.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The end of the leading number of {@code value} (after optional whitespace), or -1 when there is none. */
    private static int numericPrefixEnd(String value) {
        int length = value.length();
        int i = skipWhitespace(value);
        if (i < length && (value.charAt(i) == '+' || value.charAt(i) == '-')) {
            i++;
        }
        int digitsStart = i;
        while (i < length && isDigit(value.charAt(i))) {
            i++;
        }
        int integerDigits = i - digitsStart;
        int fractionDigits = 0;
        if (i < length && value.charAt(i) == '.') {
            int j = i + 1;
            while (j < length && isDigit(value.charAt(j))) {
                j++;
            }
            fractionDigits = j - i - 1;
            if (integerDigits > 0 || fractionDigits > 0) {
                i = j;
            }
        }
        if (integerDigits == 0 && fractionDigits == 0) {
            return -1;
        }
        if (i < length && (value.charAt(i) == 'e' || value.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < length && (value.charAt(j) == '+' || value.charAt(j) == '-')) {
                j++;
            }
            int exponentStart = j;
            while (j < length && isDigit(value.charAt(j))) {
                j++;
            }
            if (j > exponentStart) {
                i = j;
            }
        }
        return i;
    }

    /** PHP's default string comparison for {@code sort}: numeric strings by value, the rest by their bytes. */
    static int phpCompare(String left, String right) {
        if (isNumeric(left) && isNumeric(right)) {
            return Double.compare(phpDouble(left), phpDouble(right));
        }
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /** The pieces between runs of separator characters, keeping an empty first or last piece as {@code preg_split}. */
    static List<String> splitRuns(String value, IntPredicate separator) {
        List<String> pieces = new ArrayList<>();
        int length = value.length();
        int start = 0;
        int i = 0;
        while (true) {
            while (i < length && !separator.test(value.charAt(i))) {
                i++;
            }
            pieces.add(value.substring(start, i));
            if (i >= length) {
                return pieces;
            }
            while (i < length && separator.test(value.charAt(i))) {
                i++;
            }
            start = i;
            if (i >= length) {
                pieces.add("");
                return pieces;
            }
        }
    }

    private static String digits(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Digits, commas and minus signs (an id list); otherwise also lower-case letters and underscores (statuses). */
    private static String keep(String value, boolean idList) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean kept = isDigit(c) || c == ',' || c == '-' || (!idList && ((c >= 'a' && c <= 'z') || c == '_'));
            if (kept) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** PHP's {@code trim}: spaces, tabs, line breaks, NUL and vertical tabs at both ends. */
    static String phpTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isTrimmed(value.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static String trimTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isTrimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == '\u000B';
    }

    private static boolean isPhpWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f';
    }

    /** A character {@code \s} matches in PCRE without the Unicode flag. */
    private static boolean isPhpSpace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f';
    }

    /** The separators WordPress splits a tag value on besides the comma or plus: CR, LF, tab and space. */
    private static boolean isTagSpace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean hasTagSpace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (isTagSpace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
