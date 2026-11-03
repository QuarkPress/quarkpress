package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.DateBound;
import org.quarkpress.posts.DateColumn;
import org.quarkpress.posts.DateCondition;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.SearchColumn;
import org.quarkpress.posts.TaxClause;
import org.quarkpress.posts.TaxQuery;
import org.quarkpress.posts.runtime.Slugs;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.runtime.CollectionItems;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * A post type's collection as WordPress lists it: the arguments mapped to a post query ({@code include} wins over
 * {@code exclude}, {@code author_exclude} over {@code author}, {@code parent} over {@code parent_exclude}; dates are
 * exclusive bounds read in the site's zone; {@code sticky} selects or drops the sticky posts and {@code ignore_sticky}
 * off puts them first), the callback errors of the orderings, the page past the end, the offset's window with the
 * page parameter moving only the links, the items the caller may read (the totals count those only), and in the edit
 * context only the items the caller may edit. HEAD answers the headers alone.
 */
@ApplicationScoped
class PostCollection {

    private static final String POST_FORMAT = "post_format";

    @Inject
    Posts posts;

    @Inject
    PostPermissions permissions;

    @Inject
    SiteContext site;

    @Inject
    PostFields fields;

    RestResponse list(PostRoutes routes, RestRequest request) {
        Paging paging = Paging.of(request);
        String orderby = ListArgs.scalar(request.param("orderby").orElse(null));
        if (orderby.equals("relevance")
                && request.param("search").map(PhpValues::isEmpty).orElse(true)) {
            throw new RestException(RestError.of(
                    "rest_no_search_term_defined", "You need to define a search term to order by relevance.", 400));
        }
        List<Long> include = ListArgs.absoluteIds(ListArgs.bounded(request, "include"));
        if (orderby.equals("include") && include.isEmpty()) {
            throw new RestException(RestError.of(
                    "rest_orderby_include_missing_include",
                    "You need to define an include parameter to order by include.",
                    400));
        }
        Optional<PostQuery.Builder> built = query(routes, request, paging, include);
        if (built.isEmpty()) {
            return RestResponse.paged(RestJson.NODES.arrayNode(), 0, links(paging), request);
        }
        PostQuery query = built.get().build();
        PostPage page = posts.query(query);
        long total = page.found();
        if (total < 1 && paging.page() > 1) {
            // WordPress counts again without paging when a page past the first finds nothing
            total = posts.query(query.toBuilder().perPage(1).page(1).offset(0).build())
                    .found();
        }
        long maxPages = total <= 0 ? 0 : (total - 1) / paging.perPage() + 1;
        if (total > 0 && paging.page() > maxPages) {
            throw new RestException(RestError.of(
                    "rest_post_invalid_page_number",
                    "The page number requested is larger than the number of pages available.",
                    400));
        }
        if (request.method().equals("HEAD")) {
            // no body is sent, so no post is rendered
            return RestResponse.paged(RestJson.NODES.arrayNode(), total, links(paging), request);
        }
        boolean edit = request.isEditContext();
        SecurityIdentity identity = request.identity();
        Fields.Selection selection = Fields.forResponse(request, routes.schema());
        ArrayNode items = RestJson.NODES.arrayNode();
        for (PostSnapshot post : page.posts()) {
            if (edit && !permissions.can(identity, "edit_post", post)) {
                continue;
            }
            items.add(CollectionItems.inline(
                    fields.prepare(routes, post, selection, request.context(), request), request));
        }
        return RestResponse.paged(items, total, links(paging), request);
    }

    /** The paging the links are built from: the page parameter, whatever window the offset selected. */
    private static Paging links(Paging paging) {
        long start = ((long) paging.page() - 1) * paging.perPage();
        return new Paging(paging.page(), paging.perPage(), (int) Math.min(Integer.MAX_VALUE, start));
    }

    /**
     * The query of the collection's arguments; empty when it can match nothing (an include list of only zeros, or
     * sticky posts asked for when there are none).
     */
    private Optional<PostQuery.Builder> query(
            PostRoutes routes, RestRequest request, Paging paging, List<Long> include) {
        PostQuery.Builder query = PostQuery.builder().types(routes.key());
        request.param("status").ifPresent(status -> query.statuses(texts(status).toArray(String[]::new)));
        if (routes.supports("author")) {
            query.authorIn(ListArgs.positive(ListArgs.absoluteIds(ListArgs.bounded(request, "author")), UserId::of)
                    .toArray(UserId[]::new));
            query.authorNotIn(
                    ListArgs.positive(ListArgs.absoluteIds(ListArgs.bounded(request, "author_exclude")), UserId::of)
                            .toArray(UserId[]::new));
        }
        List<PostId> postIn = new ArrayList<>();
        if (!include.isEmpty()) {
            postIn.addAll(ListArgs.positive(include, PostId::of));
            if (postIn.isEmpty()) {
                return Optional.empty();
            }
        }
        Set<PostId> postNotIn = new LinkedHashSet<>(
                ListArgs.positive(ListArgs.absoluteIds(ListArgs.bounded(request, "exclude")), PostId::of));
        if (routes.supports("page-attributes")) {
            request.param("menu_order").ifPresent(order -> query.menuOrder((int) PhpValues.toLong(order)));
        }
        boolean ascending = ListArgs.scalar(request.param("order").orElse(null)).equals("asc");
        orderBy(query, ListArgs.scalar(request.param("orderby").orElse(null)), ascending);
        if (routes.type().hierarchical()) {
            query.parentIn(ListArgs.positive(ListArgs.absoluteIds(ListArgs.bounded(request, "parent")), PostId::of)
                    .toArray(PostId[]::new));
            query.parentNotIn(
                    ListArgs.positive(ListArgs.absoluteIds(ListArgs.bounded(request, "parent_exclude")), PostId::of)
                            .toArray(PostId[]::new));
        }
        request.param("search")
                .map(ListArgs::scalar)
                .filter(text -> !text.isEmpty())
                .ifPresent(query::search);
        for (String column : texts(request.param("search_columns").orElse(null))) {
            switch (column) {
                case "post_title" -> query.searchColumns(SearchColumn.TITLE);
                case "post_content" -> query.searchColumns(SearchColumn.CONTENT);
                case "post_excerpt" -> query.searchColumns(SearchColumn.EXCERPT);
                default -> {
                    // refused by the enum
                }
            }
        }
        if (ListArgs.scalar(request.param("search_semantics").orElse(null)).equals("exact")) {
            query.exactSearch(true);
        }
        request.param("slug").ifPresent(slug -> {
            List<String> slugs = ListArgs.slugList(slug, Slugs::sanitizeTitle);
            if (!slugs.isEmpty()) {
                query.nameIn(slugs.toArray(String[]::new));
            }
        });
        ZoneId zone = site.zone();
        dates(request, "after", DateColumn.DATE, true, zone, query);
        dates(request, "before", DateColumn.DATE, false, zone, query);
        dates(request, "modified_after", DateColumn.MODIFIED, true, zone, query);
        dates(request, "modified_before", DateColumn.MODIFIED, false, zone, query);
        boolean stickyFirst = false;
        if (routes.key().equals("post")) {
            Optional<JsonNode> sticky = request.param("sticky");
            if (sticky.isPresent()) {
                List<PostId> stickies = posts.stickyPosts();
                if (PhpValues.toBoolean(sticky.get())) {
                    List<PostId> selected = new ArrayList<>();
                    for (PostId id : stickies) {
                        if (postIn.isEmpty() || postIn.contains(id)) {
                            selected.add(id);
                        }
                    }
                    if (selected.isEmpty()) {
                        return Optional.empty();
                    }
                    postIn = selected;
                } else {
                    postNotIn.addAll(stickies);
                }
            }
            stickyFirst = postIn.isEmpty()
                    && !request.param("ignore_sticky").map(PhpValues::toBoolean).orElse(true);
        }
        query.postIn(postIn.toArray(PostId[]::new));
        query.postNotIn(postNotIn.toArray(PostId[]::new));
        query.stickyFirst(stickyFirst);
        if (routes.supports("post-formats")) {
            formats(request, query);
        }
        query.perPage(paging.perPage()).page(paging.page()).offset(paging.offset());
        return Optional.of(query);
    }

    /** WordPress's REST orderings: id is the post id, include and include_slugs the lists' orders. */
    private static void orderBy(PostQuery.Builder query, String orderby, boolean ascending) {
        switch (orderby) {
            case "id" -> query.orderBy(OrderKey.ID, ascending);
            case "include" -> query.orderBy(OrderKey.POST_IN, ascending);
            case "slug" -> query.orderBy(OrderKey.NAME, ascending);
            case "include_slugs" -> query.orderBy(OrderKey.NAME_IN, ascending);
            case "relevance" -> query.orderBy(OrderKey.RELEVANCE, ascending).orderBy(OrderKey.DATE, ascending);
            case "author" -> query.orderBy(OrderKey.AUTHOR, ascending);
            case "modified" -> query.orderBy(OrderKey.MODIFIED, ascending);
            case "parent" -> query.orderBy(OrderKey.PARENT, ascending);
            case "title" -> query.orderBy(OrderKey.TITLE, ascending);
            case "menu_order" -> query.orderBy(OrderKey.MENU_ORDER, ascending);
            default -> query.orderBy(OrderKey.DATE, ascending);
        }
    }

    /** An exclusive date bound on the column, the value read as a wall time of the site's zone. */
    private static void dates(
            RestRequest request, String name, DateColumn column, boolean after, ZoneId zone, PostQuery.Builder query) {
        Optional<JsonNode> value = request.param(name);
        if (value.isEmpty() || value.get().isContainerNode()) {
            return;
        }
        LocalDateTime local = localTime(value.get().asText(), zone);
        DateBound bound = DateBound.exact(local);
        query.date(after ? DateCondition.after(column, bound, false) : DateCondition.before(column, bound, false));
    }

    /**
     * The wall time, in {@code zone}, of a date-time that passed the schema's check: a date, {@code T} or a space,
     * a time, an optional fraction (dropped) and an optional {@code Z} or offset, which is honoured by converting the
     * instant into the zone; without one the text is the wall time itself. Fields past their range roll over as PHP
     * rolls them.
     */
    static LocalDateTime localTime(String text, ZoneId zone) {
        int[] at = {0};
        int year = digits(text, at, 4);
        at[0]++;
        int month = digits(text, at, 2);
        at[0]++;
        int day = digits(text, at, 2);
        at[0]++;
        int hour = digits(text, at, 2);
        at[0]++;
        int minute = digits(text, at, 2);
        at[0]++;
        int second = digits(text, at, 2);
        if (at[0] < text.length() && text.charAt(at[0]) == '.') {
            at[0]++;
            while (at[0] < text.length() && Character.isDigit(text.charAt(at[0]))) {
                at[0]++;
            }
        }
        LocalDateTime wall = LocalDateTime.of(year, 1, 1, 0, 0)
                .plusMonths(month - 1L)
                .plusDays(day - 1L)
                .plusHours(hour)
                .plusMinutes(minute)
                .plusSeconds(second);
        if (at[0] >= text.length()) {
            return wall;
        }
        char c = text.charAt(at[0]);
        ZoneOffset offset;
        if (c == 'Z') {
            offset = ZoneOffset.UTC;
        } else {
            int sign = c == '-' ? -1 : 1;
            at[0]++;
            int hours = digits(text, at, 2);
            int minutes = 0;
            if (at[0] < text.length() && text.charAt(at[0]) == ':') {
                at[0]++;
                minutes = digits(text, at, 2);
            }
            offset = ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60));
        }
        return OffsetDateTime.of(wall, offset).atZoneSameInstant(zone).toLocalDateTime();
    }

    private static int digits(String text, int[] at, int count) {
        int value = 0;
        for (int i = 0; i < count && at[0] < text.length(); i++, at[0]++) {
            value = value * 10 + (text.charAt(at[0]) - '0');
        }
        return value;
    }

    /**
     * The {@code format} filter: one group of alternatives over the post format taxonomy, {@code standard} being
     * the posts without a format term and any other slug the posts with its term.
     */
    private static void formats(RestRequest request, PostQuery.Builder query) {
        List<TaxClause> clauses = new ArrayList<>();
        for (String format : texts(request.param("format").orElse(null))) {
            clauses.add(
                    format.equals("standard")
                            ? new TaxClause.NotExists(POST_FORMAT)
                            : new TaxClause.Slugs(POST_FORMAT, List.of("post-format-" + format)));
        }
        if (!clauses.isEmpty()) {
            query.taxQuery(
                    new TaxQuery(TaxClause.Relation.AND, List.of(new TaxClause.Group(TaxClause.Relation.OR, clauses))));
        }
    }

    /** A sanitized list's items as texts; a scalar is one item; none when absent. */
    private static List<String> texts(JsonNode value) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        List<JsonNode> items = new ArrayList<>();
        if (value.isContainerNode()) {
            value.forEach(items::add);
        } else {
            items.add(value);
        }
        return ListArgs.texts(items);
    }
}
