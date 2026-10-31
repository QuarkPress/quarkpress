package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.UserId;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.security.MemberColumn;
import org.quarkpress.security.MemberOrder;
import org.quarkpress.security.MemberQuery;

/**
 * The member query of the users collection's arguments, as WordPress's users controller builds its user query: the
 * visibility restriction (authors of a published post of a type shown in REST) for a caller who may not list users and
 * did not ask for authors; {@code who=authors} as a user level other than 0, which ignores {@code roles} and
 * {@code capabilities}; role and capability names also matched as the site's keys ignoring case; the search term
 * without its wildcards in the columns the caller may search; the lists bounded.
 */
final class UserQueries {

    /** The capabilities of a user level other than 0: WordPress's authors for {@code who=authors}. */
    static final List<String> LEVELS = List.of(
            "level_1",
            "level_2",
            "level_3",
            "level_4",
            "level_5",
            "level_6",
            "level_7",
            "level_8",
            "level_9",
            "level_10");

    /** The columns a caller without {@code list_users} may search. */
    static final Set<MemberColumn> PUBLIC_COLUMNS =
            EnumSet.of(MemberColumn.ID, MemberColumn.LOGIN, MemberColumn.NICENAME, MemberColumn.DISPLAY_NAME);

    private UserQueries() {}

    /**
     * The member query of the collection's arguments for this page; empty when it can match nothing (an include list
     * of only zeros).
     */
    static Optional<MemberQuery> of(
            RestRequest request,
            List<String> restTypes,
            boolean listUsers,
            Paging paging,
            Map<String, RoleDefinition> roles) {
        MemberQuery.Builder query = MemberQuery.builder();
        List<Long> include = absoluteIds(ListArgs.bounded(request, "include"));
        if (!include.isEmpty()) {
            List<UserId> positive = positive(include);
            if (positive.isEmpty()) {
                return Optional.empty();
            }
            query.include(positive);
        }
        query.exclude(positive(absoluteIds(ListArgs.bounded(request, "exclude"))));
        List<String> slugs = texts(ListArgs.bounded(request, "slug"));
        if (!slugs.isEmpty()) {
            query.slugs(slugs);
        }
        List<String> roleNames = roleKeys(texts(ListArgs.bounded(request, "roles")), roles);
        List<String> capabilityNames = capabilityKeys(texts(ListArgs.bounded(request, "capabilities")), roles);
        boolean authorsOnly = text(request.param("who")).equals("authors");
        if (authorsOnly) {
            // WordPress's deprecated author query: a user level other than 0, and roles and capabilities ignored
            query.capabilities(LEVELS);
        } else {
            query.roles(roleNames).capabilities(capabilityNames);
        }
        Optional<String> search =
                request.param("search").map(UserQueries::scalar).filter(term -> !term.isEmpty());
        if (search.isPresent()) {
            String term = withoutWildcards(search.get());
            query.search(term).searchColumns(columns(request, term, listUsers));
        }
        published(request, restTypes, listUsers, authorsOnly).ifPresent(query::publishedPostTypes);
        query.orderBy(orderBy(text(request.param("orderby"))));
        query.ascending(!text(request.param("order")).equals("desc"));
        query.offset(paging.offset()).limit(paging.perPage());
        return Optional.of(query.build());
    }

    /**
     * The post types whose published posts make a member listed: the requested ones ({@code true}: every type shown
     * in REST), else, for a caller who may not list users and did not ask for authors, every type shown in REST.
     */
    static Optional<List<String>> published(
            RestRequest request, List<String> restTypes, boolean listUsers, boolean authorsOnly) {
        Optional<JsonNode> requested = request.param("has_published_posts");
        if (requested.isPresent()
                && requested.get().isBoolean()
                && requested.get().booleanValue()) {
            return Optional.of(restTypes);
        }
        if (requested.isPresent() && !requested.get().isBoolean()) {
            List<String> listed = texts(ListArgs.bounded(request, "has_published_posts"));
            if (!listed.isEmpty()) {
                return Optional.of(List.copyOf(new LinkedHashSet<>(listed)));
            }
        }
        return listUsers || authorsOnly ? Optional.empty() : Optional.of(restTypes);
    }

    /**
     * The columns searched: the requested ones (without {@code list_users} never the e-mail, and when none is left id,
     * login, nicename and display name), else WordPress's
     * choice: for a caller who may list users by the term (an e-mail address when it holds "@", the login and id when
     * numeric, the URL when it starts with http:// or https://, else login, URL, e-mail, nicename and display name);
     * for anyone else id, login, nicename and display name.
     */
    static Set<MemberColumn> columns(RestRequest request, String term, boolean listUsers) {
        Set<MemberColumn> columns = EnumSet.noneOf(MemberColumn.class);
        List<JsonNode> requested = ListArgs.bounded(request, "search_columns");
        for (String name : texts(requested)) {
            switch (name) {
                case "email" -> columns.add(MemberColumn.EMAIL);
                case "name" -> columns.add(MemberColumn.DISPLAY_NAME);
                case "id" -> columns.add(MemberColumn.ID);
                case "username" -> columns.add(MemberColumn.LOGIN);
                case "slug" -> columns.add(MemberColumn.NICENAME);
                default -> {
                    // the argument's enum allows no other name
                }
            }
        }
        if (!listUsers) {
            // the requested columns a caller without list_users may search; none of them: the four of them
            columns.retainAll(PUBLIC_COLUMNS);
            return columns.isEmpty() ? EnumSet.copyOf(PUBLIC_COLUMNS) : columns;
        }
        if (!requested.isEmpty()) {
            return columns;
        }
        if (term.indexOf('@') >= 0) {
            return EnumSet.of(MemberColumn.EMAIL);
        }
        if (PhpValues.isNumeric(term)) {
            return EnumSet.of(MemberColumn.LOGIN, MemberColumn.ID);
        }
        if (term.startsWith("http://") || term.startsWith("https://")) {
            return EnumSet.of(MemberColumn.URL);
        }
        return EnumSet.of(
                MemberColumn.LOGIN,
                MemberColumn.URL,
                MemberColumn.EMAIL,
                MemberColumn.NICENAME,
                MemberColumn.DISPLAY_NAME);
    }

    /** The search term without its leading and trailing {@code *}, which WordPress reads as wildcards. */
    static String withoutWildcards(String term) {
        int start = 0;
        int end = term.length();
        while (start < end && term.charAt(start) == '*') {
            start++;
        }
        while (end > start && term.charAt(end - 1) == '*') {
            end--;
        }
        return term.substring(start, end);
    }

    static MemberOrder orderBy(String orderby) {
        return switch (orderby) {
            case "id" -> MemberOrder.ID;
            case "include" -> MemberOrder.INCLUDE;
            case "registered_date" -> MemberOrder.REGISTERED;
            case "slug" -> MemberOrder.SLUG;
            case "include_slugs" -> MemberOrder.INCLUDE_SLUGS;
            case "email" -> MemberOrder.EMAIL;
            case "url" -> MemberOrder.URL;
            default -> MemberOrder.NAME;
        };
    }

    /**
     * The requested role names, each also as the site's role key it names ignoring case, as WordPress's role filter
     * matches on MySQL.
     */
    static List<String> roleKeys(List<String> requested, Map<String, RoleDefinition> roles) {
        Set<String> keys = new LinkedHashSet<>();
        for (String name : requested) {
            keys.add(name);
            for (String key : roles.keySet()) {
                if (key.equalsIgnoreCase(name)) {
                    keys.add(key);
                }
            }
        }
        return new ArrayList<>(keys);
    }

    /** The requested capability names, each also as the capability of a site role it names ignoring case. */
    static List<String> capabilityKeys(List<String> requested, Map<String, RoleDefinition> roles) {
        Set<String> keys = new LinkedHashSet<>();
        if (requested.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> known = new LinkedHashMap<>();
        for (RoleDefinition role : roles.values()) {
            for (String capability : role.capabilities().keySet()) {
                known.putIfAbsent(lower(capability), capability);
            }
            known.putIfAbsent(lower(role.key()), role.key());
        }
        for (String name : requested) {
            keys.add(name);
            String key = known.get(lower(name));
            if (key != null) {
                keys.add(key);
            }
        }
        return new ArrayList<>(keys);
    }

    static String lower(String text) {
        StringBuilder lower = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            lower.append(Character.toLowerCase(text.charAt(i)));
        }
        return lower.toString();
    }

    static String text(Optional<JsonNode> value) {
        return value.map(UserQueries::scalar).orElse("");
    }

    /** A scalar request value as PHP prints it (null as ""); a list or an object as "". */
    static String scalar(JsonNode value) {
        return value == null || value.isContainerNode() ? "" : PhpValues.toPhpString(value);
    }

    static List<String> texts(List<JsonNode> items) {
        List<String> texts = new ArrayList<>(items.size());
        items.forEach(item -> texts.add(scalar(item)));
        return texts;
    }

    /** WordPress's id lists: each item's absolute integer value, each once, in order. */
    static List<Long> absoluteIds(List<JsonNode> items) {
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode item : items) {
            long id = PhpValues.toLong(item);
            ids.add(id == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(id));
        }
        return List.copyOf(ids);
    }

    static List<UserId> positive(List<Long> ids) {
        List<UserId> positive = new ArrayList<>(ids.size());
        for (long id : ids) {
            if (id > 0) {
                positive.add(UserId.of(id));
            }
        }
        return positive;
    }
}
