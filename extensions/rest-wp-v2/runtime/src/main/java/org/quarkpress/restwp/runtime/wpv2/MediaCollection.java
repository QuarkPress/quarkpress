package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaPage;
import org.quarkpress.media.MediaQuery;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.posts.DateBound;
import org.quarkpress.posts.DateColumn;
import org.quarkpress.posts.DateCondition;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.SearchColumn;
import org.quarkpress.posts.runtime.PostCapabilityFacts;
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
import org.quarkpress.security.CapObject;
import org.quarkpress.security.Capabilities;

/**
 * The media library as WordPress's attachments collection lists it. Every attachment of the statuses asked
 * ({@code inherit} by default) is read and counted, whoever asks; each is then shown only when the caller may read it
 * (an inherited status reads through the parent) or, in the edit context, edit it, so the totals count rows the body
 * leaves out. The type filters are a union of every allowed type of each {@code media_type} group and each
 * {@code mime_type} that is exactly an allowed type; values that are none are dropped, and with none left the whole
 * library is listed. The other arguments are read as the posts collection reads them: {@code parent} (0 for the
 * unattached attachments) wins over {@code parent_exclude}, {@code author_exclude} over {@code author},
 * {@code include} over {@code exclude}; dates are exclusive bounds in the site's zone; the search also looks in the
 * stored file path, whatever columns are named. HEAD counts without reading any attachment. Each parent and cover
 * image is read once per request, and the audio and video players of the whole response are numbered in turn.
 */
@ApplicationScoped
class MediaCollection {

    @Inject
    Media media;

    @Inject
    PostPermissions permissions;

    @Inject
    SiteContext site;

    @Inject
    MediaFields fields;

    @Inject
    MediaReads reads;

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Inject
    Capabilities capabilities;

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
        Optional<MediaQuery.Builder> built = query(request, paging, include, orderby);
        if (built.isEmpty()) {
            return RestResponse.paged(RestJson.NODES.arrayNode(), 0, links(paging), request);
        }
        boolean head = request.method().equals("HEAD");
        MediaQuery.Builder query = built.get();
        MediaPage page = media.query(query.countOnly(head).build());
        long total = page.found();
        if (total < 1 && paging.page() > 1) {
            // WordPress counts again without paging when a page past the first finds nothing
            total = media.query(
                            query.countOnly(true).perPage(1).page(1).offset(0).build())
                    .found();
        }
        long maxPages = total <= 0 ? 0 : (total - 1) / paging.perPage() + 1;
        if (total > 0 && paging.page() > maxPages) {
            throw new RestException(RestError.of(
                    "rest_post_invalid_page_number",
                    "The page number requested is larger than the number of pages available.",
                    400));
        }
        if (head) {
            // no body is sent: no attachment is read, checked or rendered
            return RestResponse.paged(RestJson.NODES.arrayNode(), total, links(paging), request);
        }
        boolean edit = request.isEditContext();
        SecurityIdentity caller = request.identity();
        Function<PostId, Optional<PostSnapshot>> parents = reads::post;
        Fields.Selection selection = Fields.forResponse(request, routes.schema());
        AttachmentMarkup.Counters counters = new AttachmentMarkup.Counters();
        ArrayNode items = RestJson.NODES.arrayNode();
        for (MediaSnapshot attachment : page.items()) {
            PostSnapshot post = attachment.post();
            if (!post.site().equals(site.siteId())) {
                continue;
            }
            boolean shown = edit ? mayEdit(caller, post, parents) : permissions.restReadable(caller, post, parents);
            if (!shown) {
                continue;
            }
            items.add(CollectionItems.inline(
                    fields.prepare(routes, attachment, selection, request.context(), request, counters), request));
        }
        return RestResponse.paged(items, total, links(paging), request);
    }

    /**
     * {@code edit_post} on an attachment of the bound site, its facts (the status it inherits included) read through
     * {@code parents}, so the parents of a response are read once.
     */
    private boolean mayEdit(
            SecurityIdentity caller, PostSnapshot post, Function<PostId, Optional<PostSnapshot>> parents) {
        CapObject facts = PostCapabilityFacts.facts(post, types, statuses, parents);
        return capabilities.can(caller, "edit_post", Optional.of(facts));
    }

    /** The paging the links are built from: the page parameter, whatever window the offset selected. */
    private static Paging links(Paging paging) {
        long start = ((long) paging.page() - 1) * paging.perPage();
        return new Paging(paging.page(), paging.perPage(), (int) Math.min(Integer.MAX_VALUE, start));
    }

    /** The library query of the request's arguments; empty when it can match nothing (an include list of zeros). */
    private Optional<MediaQuery.Builder> query(RestRequest request, Paging paging, List<Long> include, String orderby) {
        MediaQuery.Builder query = MediaQuery.builder().unfiltered(true);
        query.statuses(texts(request.param("status").orElse(null)));
        List<String> types = types(request);
        if (!types.isEmpty()) {
            query.exactMimeTypes(types);
        }
        query.authorIn(ListArgs.positive(ListArgs.absoluteIds(ListArgs.bounded(request, "author")), UserId::of));
        query.authorNotIn(
                ListArgs.positive(ListArgs.absoluteIds(ListArgs.bounded(request, "author_exclude")), UserId::of));
        if (!include.isEmpty()) {
            List<PostId> ids = ListArgs.positive(include, PostId::of);
            if (ids.isEmpty()) {
                return Optional.empty();
            }
            query.include(ids);
        }
        query.exclude(ListArgs.positive(ListArgs.absoluteIds(ListArgs.bounded(request, "exclude")), PostId::of));
        // 0 names the unattached attachments in both lists
        query.parentIn(ListArgs.absoluteIds(ListArgs.bounded(request, "parent")));
        query.parentNotIn(ListArgs.absoluteIds(ListArgs.bounded(request, "parent_exclude")));
        boolean ascending = ListArgs.scalar(request.param("order").orElse(null)).equals("asc");
        orderBy(query, orderby, ascending);
        request.param("search")
                .map(ListArgs::scalar)
                .filter(text -> !text.isEmpty())
                .ifPresent(query::search);
        List<SearchColumn> columns = new ArrayList<>();
        for (String column : texts(request.param("search_columns").orElse(null))) {
            switch (column) {
                case "post_title" -> columns.add(SearchColumn.TITLE);
                case "post_content" -> columns.add(SearchColumn.CONTENT);
                case "post_excerpt" -> columns.add(SearchColumn.EXCERPT);
                default -> {
                    // refused by the enum
                }
            }
        }
        query.searchColumns(columns.toArray(SearchColumn[]::new));
        if (ListArgs.scalar(request.param("search_semantics").orElse(null)).equals("exact")) {
            query.exactSearch(true);
        }
        request.param("slug").ifPresent(slug -> {
            List<String> slugs = ListArgs.slugList(slug, Slugs::sanitizeTitle);
            if (!slugs.isEmpty()) {
                query.slugs(slugs);
            }
        });
        ZoneId zone = site.zone();
        dates(request, "after", DateColumn.DATE, true, zone, query);
        dates(request, "before", DateColumn.DATE, false, zone, query);
        dates(request, "modified_after", DateColumn.MODIFIED, true, zone, query);
        dates(request, "modified_before", DateColumn.MODIFIED, false, zone, query);
        query.perPage(paging.perPage()).page(paging.page()).offset(paging.offset());
        return Optional.of(query);
    }

    /**
     * The exact types the request's type filters name: every allowed type of each {@code media_type} group, then
     * each {@code mime_type} that is exactly (case included) one of the allowed types, each once; none when neither
     * names an allowed type.
     */
    private List<String> types(RestRequest request) {
        List<String> groups = texts(request.param("media_type").orElse(null));
        List<String> named = texts(request.param("mime_type").orElse(null));
        if (groups.isEmpty() && named.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, String>> allowed = media.allowedTypes();
        Set<String> types = new LinkedHashSet<>();
        for (String group : groups) {
            for (Map.Entry<String, String> type : allowed) {
                String mime = type.getValue();
                int slash = mime.indexOf('/');
                if (slash > 0 && mime.substring(0, slash).equals(group)) {
                    types.add(mime);
                }
            }
        }
        Set<String> allowedTypes = new LinkedHashSet<>();
        allowed.forEach(type -> allowedTypes.add(type.getValue()));
        for (String mime : named) {
            if (allowedTypes.contains(mime)) {
                types.add(mime);
            }
        }
        return List.copyOf(types);
    }

    /** WordPress's REST orderings of attachments: id is the post id, include and include_slugs the lists' orders. */
    private static void orderBy(MediaQuery.Builder query, String orderby, boolean ascending) {
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
            default -> query.orderBy(OrderKey.DATE, ascending);
        }
    }

    /** An exclusive date bound on the column, the value read as a wall time of the site's zone. */
    private static void dates(
            RestRequest request, String name, DateColumn column, boolean after, ZoneId zone, MediaQuery.Builder query) {
        Optional<JsonNode> value = request.param(name);
        if (value.isEmpty() || value.get().isContainerNode()) {
            return;
        }
        LocalDateTime local = PostCollection.localTime(value.get().asText(), zone);
        DateBound bound = DateBound.exact(local);
        query.date(after ? DateCondition.after(column, bound, false) : DateCondition.before(column, bound, false));
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
