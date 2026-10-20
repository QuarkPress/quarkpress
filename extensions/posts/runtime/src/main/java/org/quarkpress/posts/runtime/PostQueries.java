package org.quarkpress.posts.runtime;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.Filters;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.core.WordPressKeys;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostOrder;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostQueryFilter;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.QueryContext;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;

/**
 * Runs post queries as WordPress's {@code WP_Query} reads them (see {@link PostQuery}). Every query names the site;
 * conditions are written from fixed fragments with every value bound as a parameter.
 */
@ApplicationScoped
public class PostQueries {

    /** The status of a post that takes its readability from its parent. */
    private static final String INHERIT = "inherit";

    /** The page size when the site has no {@code posts_per_page} option (WordPress's default). */
    private static final long DEFAULT_PER_PAGE = 10;

    @Inject
    PostRepository posts;

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Inject
    SiteContext site;

    @Inject
    Acting acting;

    @Inject
    PostCapabilityFacts facts;

    @Inject
    @All
    List<InstanceHandle<PostQueryFilter>> filters;

    /**
     * The conditions of a query and their parameters, and the parameters of its ordering, which the count query
     * leaves out. The first condition names the site.
     */
    static final class Where {
        private final StringBuilder text = new StringBuilder("p.siteId = :site");
        private final Map<String, Object> params = new LinkedHashMap<>();
        private final Map<String, Object> orderParams = new LinkedHashMap<>();
        private int next;

        Where(SiteId site) {
            params.put("site", TenantIds.param(site));
        }

        /** A condition placeholder bound to {@code value}. */
        String param(Object value) {
            String name = "q" + next++;
            params.put(name, value);
            return ":" + name;
        }

        /** An ordering placeholder bound to {@code value}. */
        String orderParam(Object value) {
            String name = "q" + next++;
            orderParams.put(name, value);
            return ":" + name;
        }

        void and(String condition) {
            text.append(" and (").append(condition).append(')');
        }

        String text() {
            return text.toString();
        }

        void bindConditions(Query query) {
            params.forEach(query::setParameter);
        }

        void bindAll(Query query) {
            bindConditions(query);
            orderParams.forEach(query::setParameter);
        }
    }

    /** The posts of {@code siteId} matching the query after the query filters changed it. */
    PostPage run(SiteId siteId, PostQuery requested) {
        PostQuery query = Filters.apply(
                Filters.ordered(filters),
                requested,
                (filter, value) -> filter.apply(value, new QueryContext(siteId, acting.user())));
        Where where = new Where(siteId);
        List<String> queriedTypes = queriedTypes(query);
        where.and(queriedTypes.isEmpty() ? "1 = 0" : "p.type in " + where.param(queriedTypes));
        statusCondition(siteId, query, queriedTypes, where);
        authorCondition(query, where);
        parentCondition(query, where);
        idCondition(query, where);
        query.slug().ifPresent(slug -> where.and("p.slug = " + where.param(Slugs.sanitizeTitleForQuery(slug))));
        query.exactTitle().ifPresent(title -> where.and("lower(p.title) = lower(" + where.param(title) + ")"));
        if (query.menuOrder().isPresent()) {
            long menuOrder = Math.abs((long) query.menuOrder().getAsInt());
            where.and(menuOrder > Integer.MAX_VALUE ? "1 = 0" : "p.menuOrder = " + where.param((int) menuOrder));
        }
        Optional<SearchTerms.Parsed> parsed = query.searching()
                ? Optional.of(SearchTerms.parse(query.search().get(), query.sentence()))
                : Optional.empty();
        parsed.ifPresent(terms -> searchCondition(terms, where));
        TypedQuery<PostEntity> select = posts.getEntityManager()
                .createQuery(
                        "select p from PostEntity p where " + where.text() + orderClause(query, parsed, where),
                        PostEntity.class);
        where.bindAll(select);

        int perPage = perPage(query);
        if (query.singular() || perPage == -1) {
            List<PostSnapshot> rows = select.getResultList().stream()
                    .map(PostEntity::toSnapshot)
                    .filter(row -> !query.singular() || shownAlone(siteId, row, requestedStatuses(query)))
                    .toList();
            return new PostPage(rows, rows.size(), 0);
        }
        long page = query.page() == 0 ? 1 : Math.abs((long) query.page());
        long first = query.offset().isPresent() ? Math.abs((long) query.offset().getAsInt()) : (page - 1) * perPage;
        select.setFirstResult((int) Math.min(first, Integer.MAX_VALUE));
        select.setMaxResults(perPage);
        List<PostSnapshot> rows =
                select.getResultList().stream().map(PostEntity::toSnapshot).toList();
        if (rows.isEmpty()) {
            return new PostPage(rows, 0, 0);
        }
        TypedQuery<Long> count = posts.getEntityManager()
                .createQuery("select count(p) from PostEntity p where " + where.text(), Long.class);
        where.bindConditions(count);
        long found = count.getSingleResult();
        return new PostPage(rows, found, (found + perPage - 1) / perPage);
    }

    /**
     * The page size: the given one, or the site's {@code posts_per_page} option when none or 0 is given; -1 for no
     * paging; 0 counts as 1 and a size below -1 as its absolute value.
     */
    private int perPage(PostQuery query) {
        long perPage = query.perPage().orElse(0);
        if (perPage == 0) {
            perPage = site.options().number("posts_per_page", DEFAULT_PER_PAGE);
        }
        if (perPage < -1) {
            perPage = perPage == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(perPage);
        } else if (perPage == 0) {
            perPage = 1;
        }
        return (int) Math.min(perPage, Integer.MAX_VALUE);
    }

    /** The given statuses, sanitized as keys. */
    private static List<String> requestedStatuses(PostQuery query) {
        return query.statuses().stream().map(WordPressKeys::sanitize).toList();
    }

    /**
     * Whether a post found by id or slug is shown, as WordPress checks a single post: one whose effective status was
     * asked for is; else a public one is; a logged-out caller (the system included) sees no other; a protected status
     * needs {@code edit_post}, a private one {@code read_post}; any other registered status is never shown; an
     * unregistered one needs {@code edit_post}.
     */
    private boolean shownAlone(SiteId siteId, PostSnapshot row, List<String> requested) {
        PostFacts post = facts.of(row);
        String status = post.effectiveStatus();
        if (requested.contains(status)) {
            return true;
        }
        Optional<PostStatusDefinition> definition = statuses.find(status);
        if (definition.isPresent() && definition.get().publicly()) {
            return true;
        }
        if (!acting.loggedIn()) {
            return false;
        }
        if (definition.isEmpty() || definition.get().protectedStatus()) {
            return acting.can(siteId, "edit_post", Optional.of(post));
        }
        if (definition.get().privateStatus()) {
            return acting.can(siteId, "read_post", Optional.of(post));
        }
        return false;
    }

    /**
     * The types the query covers: when none is given, the searchable types for a query given a search (even an empty
     * or over-long one) that does not look for one post, and post otherwise; the searchable types for a lone {@code
     * any}; else the given ones sanitized as keys (an {@code any} among others stays a type name that matches nothing,
     * as in WordPress).
     */
    List<String> queriedTypes(PostQuery query) {
        if (query.types().isEmpty()) {
            return query.search().isPresent() && !query.singular() ? types.searchable() : List.of("post");
        }
        if (query.types().size() == 1
                && WordPressKeys.sanitize(query.types().get(0)).equals("any")) {
            return types.searchable();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String type : query.types()) {
            String key = WordPressKeys.sanitize(type);
            if (!key.isEmpty()) {
                result.add(key);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * The status condition. Given statuses need the queried types' {@code edit_posts} unless they are only publish;
     * {@code any} leaves out the statuses excluded from searches that are not named too; otherwise the registered ones
     * among them apply (none registered: no condition). A caller other than the system then sees only the posts it may
     * read. Without statuses, a query for one post applies no condition here (each row is checked once read), and any
     * other query shows published posts plus, to a logged-in user, the private ones it may read.
     */
    private void statusCondition(SiteId siteId, PostQuery query, List<String> queriedTypes, Where where) {
        if (!query.statuses().isEmpty()) {
            List<String> requested = requestedStatuses(query);
            if (requested.stream().anyMatch(status -> !status.equals("publish"))) {
                for (String type : queriedTypes) {
                    acting.require(siteId, types.capabilitiesOrPost(type).editPosts(), Optional.empty());
                }
            }
            if (requested.contains("any")) {
                List<String> excluded = statuses.excludedFromSearch().stream()
                        .filter(status -> !requested.contains(status))
                        .toList();
                if (!excluded.isEmpty()) {
                    where.and("p.status not in " + where.param(excluded));
                }
            } else {
                List<String> registered = requested.stream()
                        .filter(statuses::registered)
                        .distinct()
                        .toList();
                if (!registered.isEmpty()) {
                    where.and("p.status in " + where.param(registered));
                }
            }
            if (!acting.system()) {
                readableCondition(siteId, queriedTypes, where);
            }
            return;
        }
        if (query.singular()) {
            return;
        }
        Optional<UserId> user = acting.user();
        List<String> clauses = new ArrayList<>();
        for (String type : queriedTypes) {
            StringBuilder clause = new StringBuilder("p.type = ")
                    .append(where.param(type))
                    .append(" and (")
                    .append(in("p.status", statuses.publicStatuses(), where));
            if (user.isPresent()
                    && types.registered(type)
                    && !statuses.privateStatuses().isEmpty()) {
                String privates = in("p.status", statuses.privateStatuses(), where);
                if (acting.can(siteId, types.capabilitiesOrPost(type).readPrivatePosts(), Optional.empty())) {
                    clause.append(" or ").append(privates);
                } else {
                    clause.append(" or (")
                            .append(privates)
                            .append(" and p.authorId = ")
                            .append(where.param(user.get().value()))
                            .append(')');
                }
            }
            clauses.add(clause.append(')').toString());
        }
        where.and(clauses.isEmpty() ? "1 = 0" : "(" + String.join(") or (", clauses) + ")");
    }

    /** What a caller may see of one type's posts: others' private posts, others' posts, its own posts. */
    private record Reach(boolean readPrivate, boolean editOthers, boolean editOwn) {
        boolean everything() {
            return readPrivate && editOthers;
        }
    }

    private Reach reach(SiteId siteId, PostTypeCapabilities names, Optional<UserId> user) {
        if (user.isEmpty()) {
            return new Reach(false, false, false);
        }
        return new Reach(
                acting.can(siteId, names.readPrivatePosts(), Optional.empty()),
                acting.can(siteId, names.editOthersPosts(), Optional.empty()),
                acting.can(siteId, names.editPosts(), Optional.empty()));
    }

    /**
     * What a caller other than the system may read among posts of any status, per queried type, as REST's read check
     * decides: see {@link #visible} and, for {@code inherit} posts, {@link #inherited}. A caller that is not logged in
     * owns nothing.
     */
    private void readableCondition(SiteId siteId, List<String> queriedTypes, Where where) {
        Optional<UserId> user = acting.user();
        List<String> clauses = new ArrayList<>();
        for (String type : queriedTypes) {
            String typeIs = "p.type = " + where.param(type);
            Reach reach = reach(siteId, types.capabilitiesOrPost(type), user);
            if (reach.everything()) {
                clauses.add(typeIs);
                continue;
            }
            clauses.add(typeIs + " and (" + visible("p", reach, user, where) + " or "
                    + inherited(siteId, reach, user, where) + ")");
        }
        where.and(clauses.isEmpty() ? "1 = 0" : "(" + String.join(") or (", clauses) + ")");
    }

    /**
     * The posts of {@code alias} a caller with {@code reach} may read, apart from {@code inherit} ones: published
     * ones; private ones with the type's {@code read_private_posts}, else its own; posts of the other registered
     * statuses with its {@code edit_others_posts}, else its own with its {@code edit_posts}; posts of unregistered
     * statuses only with its {@code edit_others_posts}.
     */
    private String visible(String alias, Reach reach, Optional<UserId> user, Where where) {
        String status = alias + ".status";
        List<String> publicStatuses = statuses.publicStatuses();
        List<String> privateStatuses = statuses.privateStatuses();
        List<String> visible = new ArrayList<>();
        visible.add(in(status, publicStatuses, where));
        if (reach.readPrivate()) {
            visible.add(in(status, privateStatuses, where));
        } else if (user.isPresent()) {
            visible.add("(" + in(status, privateStatuses, where) + " and " + own(alias, user.get(), where) + ")");
        }
        if (reach.editOthers()) {
            List<String> shownElsewhere = new ArrayList<>(publicStatuses);
            shownElsewhere.addAll(privateStatuses);
            shownElsewhere.add(INHERIT);
            visible.add(status + " not in " + where.param(shownElsewhere));
        } else if (reach.editOwn()) {
            List<String> others = statuses.all().stream()
                    .map(PostStatusDefinition::name)
                    .filter(name ->
                            !publicStatuses.contains(name) && !privateStatuses.contains(name) && !name.equals(INHERIT))
                    .toList();
            visible.add("(" + in(status, others, where) + " and " + own(alias, user.get(), where) + ")");
        }
        return "(" + String.join(" or ", visible) + ")";
    }

    /**
     * The {@code inherit} posts of a type the caller may read with {@code rowReach} (the type's reach), as REST reads
     * them: one without parent; the caller's own; one whose parent is visible by the parent type's rules (followed one
     * level); or one the row's own type lets the caller read with the parent's status ({@code read_private_posts} for
     * a private parent, {@code edit_others_posts} for any status that is neither public nor private).
     */
    private String inherited(SiteId siteId, Reach rowReach, Optional<UserId> user, Where where) {
        List<String> parents = new ArrayList<>();
        List<String> registered = new ArrayList<>();
        for (PostTypeDefinition type : types.all()) {
            registered.add(type.key());
            Reach reach = reach(siteId, types.capabilitiesOrPost(type.key()), user);
            parents.add("pp.type = " + where.param(type.key())
                    + (reach.everything() ? "" : " and " + visible("pp", reach, user, where)));
        }
        Reach unregistered = reach(siteId, types.capabilitiesOrPost("post"), user);
        parents.add((registered.isEmpty() ? "1 = 1" : "pp.type not in " + where.param(registered))
                + (unregistered.everything() ? "" : " and " + visible("pp", unregistered, user, where)));
        List<String> publicOrPrivate = new ArrayList<>(statuses.publicStatuses());
        publicOrPrivate.addAll(statuses.privateStatuses());
        if (rowReach.readPrivate()) {
            parents.add(in("pp.status", statuses.privateStatuses(), where));
        }
        if (rowReach.editOthers()) {
            parents.add(publicOrPrivate.isEmpty() ? "1 = 1" : "pp.status not in " + where.param(publicOrPrivate));
        }
        String owned = user.map(u -> " or " + own("p", u, where)).orElse("");
        return "(p.status = " + where.param(INHERIT) + " and (p.parentId is null" + owned
                + " or exists (select 1 from PostEntity pp where pp.siteId = :site"
                + " and pp.id = p.parentId and ((" + String.join(") or (", parents) + ")))))";
    }

    private static String own(String alias, UserId user, Where where) {
        return alias + ".authorId = " + where.param(user.value());
    }

    /** {@code column in (values)}; false for no values. */
    private static String in(String column, List<String> values, Where where) {
        return values.isEmpty() ? "1 = 0" : column + " in " + where.param(values);
    }

    private static void authorCondition(PostQuery query, Where where) {
        if (!query.authorNotIn().isEmpty()) {
            where.and("p.authorId is null or p.authorId not in " + where.param(userIds(query.authorNotIn())));
            return;
        }
        Set<UserId> in = new LinkedHashSet<>(query.authorIn());
        query.author().ifPresent(in::add);
        if (!in.isEmpty()) {
            where.and("p.authorId in " + where.param(userIds(in)));
        }
    }

    private static void parentCondition(PostQuery query, Where where) {
        if (query.parent().isPresent()) {
            long parent = query.parent().getAsLong();
            where.and(parent == 0 ? "p.parentId is null" : "p.parentId = " + where.param(parent));
        } else if (!query.parentIn().isEmpty()) {
            where.and("p.parentId in " + where.param(postIds(query.parentIn())));
        } else if (!query.parentNotIn().isEmpty()) {
            where.and("p.parentId is null or p.parentId not in " + where.param(postIds(query.parentNotIn())));
        }
    }

    private static void idCondition(PostQuery query, Where where) {
        if (query.id().isPresent()) {
            where.and("p.id = " + where.param(query.id().get().value()));
        } else if (!query.postIn().isEmpty()) {
            where.and("p.id in " + where.param(postIds(query.postIn())));
        } else if (!query.postNotIn().isEmpty()) {
            where.and("p.id not in " + where.param(postIds(query.postNotIn())));
        }
    }

    /**
     * The search condition: each term in the title, the excerpt or the content, each excluded term in none of them;
     * password-protected posts only for a logged-in caller.
     */
    private void searchCondition(SearchTerms.Parsed parsed, Where where) {
        for (String term : parsed.positive()) {
            String like = where.param(like(term));
            where.and(contains("p.title", like) + " or " + contains("p.excerpt", like) + " or "
                    + contains("p.content", like));
        }
        for (String term : parsed.excluded()) {
            String like = where.param(like(term));
            where.and(lacks("p.title", like) + " and " + lacks("p.excerpt", like) + " and " + lacks("p.content", like));
        }
        if (!acting.loggedIn()) {
            where.and("p.password = ''");
        }
    }

    /** {@code column} holds the pattern bound to {@code like}, ignoring case. */
    private static String contains(String column, String like) {
        return "lower(" + column + ") like lower(" + like + ") escape '!'";
    }

    /** {@code column} does not hold the pattern bound to {@code like}, ignoring case (the columns are never null). */
    private static String lacks(String column, String like) {
        return "lower(" + column + ") not like lower(" + like + ") escape '!'";
    }

    /** {@code %text%} with {@code !}, {@code %} and {@code _} escaped by {@code !}. */
    static String like(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('%');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                out.append('!');
            }
            out.append(c);
        }
        return out.append('%').toString();
    }

    /**
     * The ORDER BY clause (see {@link OrderKey}). A search with no key, or with {@code RELEVANCE} first, orders by
     * rank (when WordPress ranks it), then by date in the relevance key's direction (newest first without one), then
     * by id the same way; the keys after the relevance key are dropped. Otherwise: the keys that apply, newest first
     * when none does, then the id in the first key's direction; nothing for {@code NONE}.
     */
    String orderClause(PostQuery query, Optional<SearchTerms.Parsed> parsed, Where where) {
        List<PostOrder> requested = query.orderBy();
        if (parsed.isPresent() && (requested.isEmpty() || requested.get(0).key() == OrderKey.RELEVANCE)) {
            String direction =
                    direction(!requested.isEmpty() && requested.get(0).ascending());
            String byDate = expression(OrderKey.DATE) + direction + ", " + expression(OrderKey.ID) + direction;
            return rank(parsed.get(), where)
                    .map(rank -> " order by " + rank + ", " + byDate)
                    .orElse(" order by " + byDate);
        }
        List<PostOrder> orders = new ArrayList<>();
        for (PostOrder order : requested) {
            if (order.key() == OrderKey.NONE) {
                return "";
            }
            if ((order.key() == OrderKey.POST_IN && query.postIn().isEmpty()) || order.key() == OrderKey.RELEVANCE) {
                continue;
            }
            orders.add(order);
        }
        if (orders.isEmpty()) {
            orders.add(new PostOrder(OrderKey.DATE, false));
        }
        List<String> parts = new ArrayList<>();
        for (PostOrder order : orders) {
            parts.add(
                    order.key() == OrderKey.POST_IN
                            ? postInPosition(query.postIn(), where)
                            : expression(order.key()) + direction(order.ascending()));
        }
        PostOrder first = orders.get(0);
        if (first.key() != OrderKey.POST_IN) {
            parts.add(expression(OrderKey.ID) + direction(first.ascending()));
        }
        return " order by " + String.join(", ", parts);
    }

    /**
     * WordPress's search rank, as an ascending expression; none for a search without positive terms, for the search
     * {@code "0"} once line breaks are removed (which PHP counts as empty), or when no rank applies (seven or more
     * positive terms and a hyphen starting a word). With several raw tokens: the whole string in the title, every
     * positive term in the title (up to six terms), any of them (two to six terms), the whole string in the excerpt,
     * then in the content (the whole string only when no hyphen starts a word), then the rest. With one token: title
     * matches first.
     */
    private static Optional<String> rank(SearchTerms.Parsed parsed, Where where) {
        List<String> positive = parsed.positive();
        if (positive.isEmpty() || PostRules.phpEmpty(parsed.cleaned())) {
            return Optional.empty();
        }
        if (parsed.rawCount() <= 1) {
            return Optional.of(
                    "case when " + contains("p.title", where.orderParam(like(positive.get(0)))) + " then 0 else 1 end");
        }
        boolean sentenceRank = !parsed.sentenceRankSkipped();
        String whole = sentenceRank ? where.orderParam(like(parsed.cleaned())) : null;
        StringBuilder rank = new StringBuilder();
        if (sentenceRank) {
            rank.append(" when ").append(contains("p.title", whole)).append(" then 1");
        }
        if (positive.size() < 7) {
            List<String> titleTerms = new ArrayList<>();
            for (String term : positive) {
                titleTerms.add(contains("p.title", where.orderParam(like(term))));
            }
            rank.append(" when ").append(String.join(" and ", titleTerms)).append(" then 2");
            if (positive.size() > 1) {
                rank.append(" when ").append(String.join(" or ", titleTerms)).append(" then 3");
            }
        }
        if (sentenceRank) {
            rank.append(" when ").append(contains("p.excerpt", whole)).append(" then 4");
            rank.append(" when ").append(contains("p.content", whole)).append(" then 5");
        }
        if (rank.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("case" + rank + " else 6 end");
    }

    static String direction(boolean ascending) {
        return ascending ? " asc" : " desc";
    }

    /** The expression a key orders by; the post date is the GMT date, or the modification date while it floats. */
    static String expression(OrderKey key) {
        return switch (key) {
            case DATE -> "coalesce(p.publishedAt, p.modifiedAt)";
            case MODIFIED -> "p.modifiedAt";
            case TITLE -> "lower(p.title)";
            case NAME -> "p.slug";
            case AUTHOR -> "coalesce(p.authorId, 0)";
            case PARENT -> "coalesce(p.parentId, 0)";
            case TYPE -> "p.type";
            case ID -> "p.id";
            case MENU_ORDER -> "p.menuOrder";
            case POST_IN, RELEVANCE, NONE -> throw new IllegalArgumentException("no expression for " + key);
        };
    }

    /** The position of the post in the post-in list, as an ascending rank (ids not listed would come last). */
    private static String postInPosition(List<PostId> postIn, Where where) {
        StringBuilder position = new StringBuilder("case");
        for (int i = 0; i < postIn.size(); i++) {
            position.append(" when p.id = ")
                    .append(where.orderParam(postIn.get(i).value()))
                    .append(" then ")
                    .append(i);
        }
        return position.append(" else ").append(postIn.size()).append(" end").toString();
    }

    private static List<Long> userIds(Set<UserId> users) {
        return users.stream().map(UserId::value).toList();
    }

    private static List<Long> postIds(Collection<PostId> ids) {
        return ids.stream().map(PostId::value).toList();
    }
}
