package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.OptionWrites;
import org.quarkpress.security.Roles;
import org.quarkpress.security.SiteRoles;

/**
 * Site role tables kept in the {@code qp_user_roles} option. A change runs in a transaction bound to the site: the
 * stored table (stored from the defaults first if the site has none) is locked, changed and written whole, so
 * concurrent changes apply one after the other.
 */
@ApplicationScoped
public class JpaSiteRoles implements SiteRoles {

    static final String DEFAULT_ROLE = "default_role";

    @Inject
    SiteRoleTables tables;

    @Inject
    Roles roles;

    @Inject
    CapabilityGuard guard;

    @Inject
    CapabilityReads reads;

    @Inject
    CheckSite checkSite;

    @Inject
    InstallMode mode;

    @Inject
    SiteScope scope;

    @Inject
    OptionWrites writes;

    @Inject
    OptionRepository options;

    @Override
    public List<RoleDefinition> roles(SiteId site) {
        return tables.forSite(target(site));
    }

    @Override
    public Optional<RoleDefinition> role(SiteId site, String key) {
        return Optional.ofNullable(tables.byKey(target(site)).get(key));
    }

    @Override
    public Optional<RoleDefinition> add(
            SiteId site, String key, String displayName, Map<String, Boolean> capabilities) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(capabilities, "capabilities");
        guard.require(site, "manage_options", Optional.empty());
        SiteId target = target(site);
        if (key.isEmpty() || key.equals("0")) {
            return Optional.empty();
        }
        guard.requireMayGive(target, capabilities);
        RoleDefinition role = new RoleDefinition(key, displayName, capabilities);
        return change(target, table -> {
            if (table.containsKey(key)) {
                return new Edit<>(Optional.<RoleDefinition>empty(), false);
            }
            table.put(key, role);
            return new Edit<>(Optional.of(role), true);
        });
    }

    @Override
    public boolean remove(SiteId site, String key) {
        Objects.requireNonNull(key, "key");
        guard.require(site, "manage_options", Optional.empty());
        SiteId target = target(site);
        return change(target, table -> {
            if (table.remove(key) == null) {
                return new Edit<>(false, false);
            }
            Optional<JsonNode> defaultRole = reads.siteOption(target, DEFAULT_ROLE);
            if (defaultRole.isPresent()
                    && defaultRole.get().isTextual()
                    && defaultRole.get().textValue().equals(key)) {
                writes.set(target, DEFAULT_ROLE, TextNode.valueOf(BuiltInRoles.SUBSCRIBER), null);
            }
            return new Edit<>(true, true);
        });
    }

    @Override
    public boolean setCapability(SiteId site, String role, String capability, boolean grant) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(capability, "capability");
        guard.require(site, "manage_options", Optional.empty());
        SiteId target = target(site);
        guard.requireMayGive(target, Map.of(capability, grant));
        return change(target, table -> {
            RoleDefinition found = table.get(role);
            if (found == null) {
                return new Edit<>(false, false);
            }
            table.put(role, found.withCapability(capability, grant));
            return new Edit<>(true, true);
        });
    }

    @Override
    public boolean removeCapability(SiteId site, String role, String capability) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(capability, "capability");
        guard.require(site, "manage_options", Optional.empty());
        SiteId target = target(site);
        return change(target, table -> {
            RoleDefinition found = table.get(role);
            if (found == null || !found.capabilities().containsKey(capability)) {
                return new Edit<>(false, false);
            }
            table.put(role, found.withoutCapability(capability));
            return new Edit<>(true, true);
        });
    }

    /** What a change returns, and whether it changed the table. */
    private record Edit<T>(T result, boolean changed) {}

    /**
     * The site a role change or read is for. On a single site WordPress has no site dimension, so the main site; in a
     * network, the site given (as {@code JpaCapabilities.target}).
     */
    private SiteId target(SiteId site) {
        Objects.requireNonNull(site, "site");
        return mode.multisite() ? site : checkSite.main();
    }

    private <T> T change(SiteId site, Function<Map<String, RoleDefinition>, Edit<T>> edit) {
        try {
            return scope.call(site, () -> {
                Optional<OptionEntity> stored = options.lockOption(site, SiteRoleTables.OPTION);
                if (stored.isEmpty()) {
                    // Untouched site: change the defaults, and store the table only if something changed.
                    Map<String, RoleDefinition> table = new LinkedHashMap<>(RoleTables.byKey(roles.defaults()));
                    Edit<T> outcome = edit.apply(table);
                    if (!outcome.changed()) {
                        return outcome.result();
                    }
                    if (writes.add(
                            site, SiteRoleTables.OPTION, RoleTables.toJson(new ArrayList<>(table.values())), true)) {
                        return outcome.result();
                    }
                    // A concurrent first change won the insert: apply this one to its table. The plain lookup of
                    // lockOption would read the older snapshot on MySQL, so lock by name.
                    stored = options.findOptionForUpdate(site, SiteRoleTables.OPTION);
                }
                OptionEntity row = stored.orElseThrow(() -> new IllegalStateException(
                        "the role table of site " + site.value() + " disappeared while it was being changed"));
                Map<String, RoleDefinition> table =
                        new LinkedHashMap<>(RoleTables.byKey(RoleTables.fromJson(row.value)));
                Edit<T> outcome = edit.apply(table);
                if (outcome.changed()) {
                    writes.set(site, SiteRoleTables.OPTION, RoleTables.toJson(new ArrayList<>(table.values())), null);
                }
                return outcome.result();
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
