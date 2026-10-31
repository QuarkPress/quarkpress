package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.persistence.runtime.Dialects;
import org.quarkpress.security.MemberColumn;
import org.quarkpress.security.MemberPage;
import org.quarkpress.security.MemberQuery;

/**
 * Lists a site's members with one native statement per database kind over the site's membership rows joined to the
 * profiles (on a single site, over every profile, joined to its membership row when it has one, as WordPress's
 * single-site user query lists every user), run twice: once to count the matches, once for the page. Every value is a
 * bound parameter. A profile without a membership row stores no role or capability, so a role or capability filter
 * never matches it.
 *
 * <p>Roles and capabilities filter together, as WordPress's user query does: the requested roles, the requested
 * capability names and every role of the site's table that grants one of those capabilities form one list of names,
 * and a member matches when any of them is a key it stores, among its roles or among its individual capabilities,
 * whatever the stored value (a denied capability is still a stored key). Capabilities are not evaluated.
 */
@SiteScopedNativeSql
@ApplicationScoped
public class MemberListing {

    @Inject
    SiteMembershipRepository memberships;

    @Inject
    SiteRoleTables tables;

    @ActivateRequestContext
    @Transactional
    MemberPage list(SiteId site, MemberQuery query, boolean everyProfile) {
        Session session = memberships.getSession();
        boolean postgresql = Dialects.postgresql(session);
        List<RoleDefinition> siteRoles = query.capabilities().isEmpty() ? List.of() : tables.forSite(site);
        Statement where = where(site, query, siteRoles, postgresql, everyProfile);

        NativeQuery<Object> count = session.createNativeQuery("select count(*)" + where.sql, Object.class);
        where.bind(count);
        long total = ((Number) count.getSingleResult()).longValue();

        Statement page = new Statement(postgresql);
        page.append("select u.id").append(where.sql.toString());
        page.parameters.putAll(where.parameters);
        page.append(" order by ");
        order(page, query);
        page.append(" limit ").param(query.limit()).append(" offset ").param(query.offset());
        NativeQuery<Object> rows = session.createNativeQuery(page.sql.toString(), Object.class);
        page.bind(rows);
        List<UserId> users = new ArrayList<>();
        for (Object id : rows.getResultList()) {
            users.add(UserId.of(((Number) id).longValue()));
        }
        return new MemberPage(users, total);
    }

    private static Statement where(
            SiteId site, MemberQuery query, List<RoleDefinition> siteRoles, boolean postgresql, boolean everyProfile) {
        Statement where = new Statement(postgresql);
        if (everyProfile) {
            // a single site: every profile, its membership row (roles and capabilities) when it has one
            where.append(" from qp_user_profile u left join qp_site_membership m on m.user_id = u.id and m.site_id = ")
                    .param(site.value())
                    .append(" where 1 = 1");
        } else {
            where.append(" from qp_site_membership m join qp_user_profile u on u.id = m.user_id where m.site_id = ")
                    .param(site.value());
        }
        if (!query.include().isEmpty()) {
            where.append(" and u.id in (").ids(query.include()).append(")");
        }
        // as in WordPress, exclude applies only without include
        if (query.include().isEmpty() && !query.exclude().isEmpty()) {
            where.append(" and u.id not in (").ids(query.exclude()).append(")");
        }
        if (!query.slugs().isEmpty()) {
            // no nicename ends in white space, but MySQL's PAD SPACE collation would match "bob " to "bob"
            List<String> slugs = new ArrayList<>();
            for (String slug : query.slugs()) {
                if (slug.isEmpty() || !Character.isWhitespace(slug.charAt(slug.length() - 1))) {
                    slugs.add(slug);
                }
            }
            if (slugs.isEmpty()) {
                where.append(" and 1 = 0");
            } else {
                where.append(" and u.nicename in (").texts(slugs).append(")");
            }
        }
        Set<String> names = new LinkedHashSet<>(query.roles());
        for (RoleDefinition role : siteRoles) {
            for (String capability : query.capabilities()) {
                if (Boolean.TRUE.equals(role.capabilities().get(capability))) {
                    names.add(role.key());
                    break;
                }
            }
        }
        names.addAll(query.capabilities());
        if (!names.isEmpty()) {
            where.append(" and ");
            hasKey(where, names);
        }
        query.search().ifPresent(term -> {
            where.append(" and ");
            search(where, term, query.searchColumns());
        });
        query.publishedPostTypes().ifPresent(types -> {
            if (types.isEmpty()) {
                where.append(" and 1 = 0");
            } else {
                where.append(" and exists (select 1 from qp_post p where p.site_id = ")
                        .param(site.value())
                        .append(" and p.author_id = u.id and p.status = 'publish' and p.type in (")
                        .texts(types)
                        .append("))");
            }
        });
        return where;
    }

    /** The member has any of {@code names} among its stored roles or as keys of its individual capabilities. */
    private static void hasKey(Statement where, Collection<String> names) {
        if (where.postgresql) {
            // the functions behind the ?| operator, which a native query cannot contain (? marks a JDBC parameter)
            where.append("(jsonb_exists_any(cast(m.roles as jsonb), cast(array[")
                    .texts(names)
                    .append("] as text[])) or jsonb_exists_any(cast(m.capabilities as jsonb), cast(array[")
                    .texts(names)
                    .append("] as text[])))");
        } else {
            ArrayNode json = JsonNodeFactory.instance.arrayNode();
            names.forEach(json::add);
            where.append("(coalesce(json_overlaps(m.roles, ")
                    .param(json.toString())
                    .append("), false) or coalesce(json_overlaps(json_keys(m.capabilities), ")
                    .param(json.toString())
                    .append("), false))");
        }
    }

    private static void search(Statement where, String term, Set<MemberColumn> columns) {
        String pattern = "%" + escape(term.toLowerCase(Locale.ROOT)) + "%";
        List<String> tests = new ArrayList<>();
        for (MemberColumn column : MemberColumn.values()) {
            if (!columns.contains(column)) {
                continue;
            }
            switch (column) {
                case ID -> digits(term).ifPresent(id -> tests.add("u.id = " + where.placeholder(id)));
                case LOGIN, NICENAME -> tests.add(like(where, "u.nicename", pattern));
                case DISPLAY_NAME -> tests.add(like(where, "u.display_name", pattern));
                case EMAIL -> tests.add(like(where, "u.email", pattern));
                case URL -> {
                    // QuarkPress keeps no user URL: nothing matches
                }
            }
        }
        if (tests.isEmpty()) {
            where.append("1 = 0");
            return;
        }
        where.append("(").append(String.join(" or ", tests)).append(")");
    }

    private static String like(Statement where, String column, String pattern) {
        return "lower(" + column + ") like " + where.placeholder(pattern) + " escape '!'";
    }

    /** {@code text} with {@code !}, {@code %} and {@code _} escaped by {@code !}. */
    static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                escaped.append('!');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** The id a digits-only term names; empty for any other term or one beyond the id range. */
    private static Optional<Long> digits(String term) {
        if (term.isEmpty() || term.length() > 18) {
            return Optional.empty();
        }
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (c < '0' || c > '9') {
                return Optional.empty();
            }
        }
        return Optional.of(Long.parseLong(term));
    }

    private static void order(Statement page, MemberQuery query) {
        String direction = query.ascending() ? " asc" : " desc";
        switch (query.orderBy()) {
            case NAME -> page.append("lower(u.display_name)").append(direction).append(", ");
            case SLUG -> page.append("lower(u.nicename)").append(direction).append(", ");
            case REGISTERED -> page.append("u.created_at").append(direction).append(", ");
            case EMAIL ->
                page.append("case when u.email is null then 0 else 1 end")
                        .append(direction)
                        .append(", lower(u.email)")
                        .append(direction)
                        .append(", ");
            // without include, WordPress orders by login, which is the nicename here
            case INCLUDE -> {
                if (query.include().isEmpty()) {
                    page.append("lower(u.nicename)").append(direction).append(", ");
                } else {
                    positions(page, "u.id", ids(query.include()), direction);
                }
            }
            // WordPress orders by the slugs' positions ascending, whatever the direction
            case INCLUDE_SLUGS -> positions(page, "u.nicename", new ArrayList<Object>(query.slugs()), " asc");
            case ID, URL -> {
                // by id only
            }
        }
        page.append("u.id").append(direction);
    }

    private static List<Object> ids(List<UserId> users) {
        List<Object> ids = new ArrayList<>();
        users.forEach(user -> ids.add(user.value()));
        return ids;
    }

    /** Orders by the position of {@code column}'s value in {@code values}; values not listed come last. */
    private static void positions(Statement page, String column, List<Object> values, String direction) {
        if (values.isEmpty()) {
            return;
        }
        page.append("case ").append(column);
        for (int i = 0; i < values.size(); i++) {
            // positions are numbers this class makes, written as literals so that the result type is plain
            page.append(" when ").param(values.get(i)).append(" then ").append(String.valueOf(i));
        }
        page.append(" else ")
                .append(String.valueOf(values.size()))
                .append(" end")
                .append(direction)
                .append(", ");
    }

    /** SQL text with named parameters {@code p0}, {@code p1}, ... and their values. */
    private static final class Statement {

        final boolean postgresql;
        final StringBuilder sql = new StringBuilder();
        final Map<String, Object> parameters = new LinkedHashMap<>();

        Statement(boolean postgresql) {
            this.postgresql = postgresql;
        }

        Statement append(String text) {
            sql.append(text);
            return this;
        }

        Statement param(Object value) {
            sql.append(placeholder(value));
            return this;
        }

        /** A new parameter for {@code value}, to be placed in the text by the caller. */
        String placeholder(Object value) {
            String name = "p" + parameters.size();
            parameters.put(name, value);
            return ":" + name;
        }

        Statement ids(List<UserId> users) {
            return list(MemberListing.ids(users));
        }

        Statement texts(Collection<String> values) {
            return list(new ArrayList<Object>(values));
        }

        private Statement list(List<Object> values) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                param(values.get(i));
            }
            return this;
        }

        void bind(NativeQuery<?> query) {
            parameters.forEach(query::setParameter);
        }
    }
}
