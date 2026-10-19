package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.Session;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.persistence.runtime.Dialects;
import org.quarkpress.persistence.runtime.QuarkPressJsonFormatMapper;

/**
 * Reads what capability checks need, for an explicit site, with native SQL: a check may be made for a site other than
 * the one the request is bound to, from the network scope, from an unresolved request or from a worker thread without
 * a request. Every native statement on a site table names its site. Each such read joins the caller's transaction or
 * runs in a short one of its own, with a request context activated when none is. The one read across sites, a user's
 * memberships, is a JPA query in the network scope.
 *
 * <p>The reads take any site and open the network scope, so they are reachable from this package only: application
 * code asks {@code Capabilities}.
 */
@SiteScopedNativeSql
@ApplicationScoped
public class CapabilityReads {

    /** A stored membership: role keys in order and individual capabilities (values read as PHP reads truth). */
    public record StoredMembership(
            SiteId site, UserId user, List<String> roles, Map<String, Boolean> capabilities, Instant createdAt) {}

    private static final String PG_MEMBERSHIP =
            "select cast(roles as text) as roles, cast(capabilities as text) as capabilities, created_at"
                    + " from qp_site_membership where site_id = ?1 and user_id = ?2";
    private static final String MYSQL_MEMBERSHIP =
            "select roles, capabilities, created_at" + " from qp_site_membership where site_id = ?1 and user_id = ?2";
    private static final String PG_OPTION =
            "select cast(value as text) from qp_option where site_id = ?1 and name = ?2";
    private static final String MYSQL_OPTION = "select value from qp_option where site_id = ?1 and name = ?2";
    private static final String PG_NETWORK_OPTION = "select cast(value as text) from qp_network_option where name = ?1";
    private static final String MYSQL_NETWORK_OPTION = "select value from qp_network_option where name = ?1";
    private static final String NETWORK_ADMIN = "select count(*) from qp_network_admin where user_id = ?1";
    private static final String PROFILE = "select count(*) from qp_user_profile where id = ?1";
    private static final String NETWORK_ADMINS = "select user_id from qp_network_admin order by user_id";

    @Inject
    SiteMembershipRepository memberships;

    @Inject
    NetworkScope network;

    @ActivateRequestContext
    @Transactional
    Optional<StoredMembership> membership(SiteId site, UserId user) {
        Session session = memberships.getSession();
        // typed scalars: the creation time is read as the persistence unit reads instants (stored as UTC)
        List<Object[]> rows = session.createNativeQuery(
                        Dialects.postgresql(session) ? PG_MEMBERSHIP : MYSQL_MEMBERSHIP, Object[].class)
                .addScalar("roles", String.class)
                .addScalar("capabilities", String.class)
                .addScalar("created_at", Instant.class)
                .setParameter(1, site.value())
                .setParameter(2, user.value())
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(new StoredMembership(
                site, user, roles((String) row[0]), capabilities((String) row[1]), (Instant) row[2]));
    }

    /**
     * Every membership of the user, by site id. Rows of several sites cannot be read by native SQL that names one site,
     * so this is a JPA query in the network scope, where the site filter lets every site's rows through (this class is
     * in a package that may open it). It runs in a transaction of its own on its own thread, unless the caller is bound
     * to the network, and therefore sees committed rows only.
     */
    List<StoredMembership> memberships(UserId user) {
        try {
            return network.call(() -> memberships.list("userId = ?1 order by siteId", user.value()).stream()
                    .map(row -> new StoredMembership(
                            row.site(),
                            user,
                            roles(row.roles.toString()),
                            capabilities(row.capabilities.toString()),
                            row.createdAt))
                    .toList());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @ActivateRequestContext
    @Transactional
    Optional<JsonNode> siteOption(SiteId site, String name) {
        Session session = memberships.getSession();
        List<String> values = session.createNativeQuery(
                        Dialects.postgresql(session) ? PG_OPTION : MYSQL_OPTION, String.class)
                .setParameter(1, site.value())
                .setParameter(2, name)
                .getResultList();
        return values.isEmpty() ? Optional.empty() : Optional.of(QuarkPressJsonFormatMapper.read(values.get(0)));
    }

    @ActivateRequestContext
    @Transactional
    Optional<JsonNode> networkOption(String name) {
        Session session = memberships.getSession();
        List<String> values = session.createNativeQuery(
                        Dialects.postgresql(session) ? PG_NETWORK_OPTION : MYSQL_NETWORK_OPTION, String.class)
                .setParameter(1, name)
                .getResultList();
        return values.isEmpty() ? Optional.empty() : Optional.of(QuarkPressJsonFormatMapper.read(values.get(0)));
    }

    /** Whether a profile with this id exists (a network table). */
    @ActivateRequestContext
    @Transactional
    boolean profileExists(UserId user) {
        Number count = (Number) memberships
                .getSession()
                .createNativeQuery(PROFILE, Object.class)
                .setParameter(1, user.value())
                .getSingleResult();
        return count.longValue() > 0;
    }

    @ActivateRequestContext
    @Transactional
    boolean networkAdmin(UserId user) {
        Number count = (Number) memberships
                .getSession()
                .createNativeQuery(NETWORK_ADMIN, Object.class)
                .setParameter(1, user.value())
                .getSingleResult();
        return count.longValue() > 0;
    }

    @ActivateRequestContext
    @Transactional
    List<UserId> networkAdmins() {
        List<UserId> admins = new ArrayList<>();
        for (Object id : memberships
                .getSession()
                .createNativeQuery(NETWORK_ADMINS, Object.class)
                .getResultList()) {
            admins.add(UserId.of(((Number) id).longValue()));
        }
        return admins;
    }

    /** The role keys of a stored array, in order; anything that is not text is skipped. */
    static List<String> roles(String json) {
        JsonNode node = QuarkPressJsonFormatMapper.read(json);
        List<String> roles = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (element.isTextual()) {
                    roles.add(element.textValue());
                }
            }
        }
        return Collections.unmodifiableList(roles);
    }

    /** Individual capabilities of a stored object, in order, values read as PHP reads truth. */
    static Map<String, Boolean> capabilities(String json) {
        JsonNode node = QuarkPressJsonFormatMapper.read(json);
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                capabilities.put(field.getKey(), PhpTruth.truthy(field.getValue()));
            }
        }
        return Collections.unmodifiableMap(capabilities);
    }
}
