package org.quarkpress.testing.wpscenario;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** One operation of a scenario. Sites are referenced by creation order: 0 is the main site, k the k-th created. */
public sealed interface ScenarioOp {

    /** The operation's name in the scenario format. */
    String kind();

    /**
     * A site created as the network administrator creates one, with an explicit address; an empty title means "no
     * title" (WordPress then names the site {@code Site <id>}).
     */
    record CreateSite(String domain, String path, String title) implements ScenarioOp {
        public CreateSite {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(title, "title");
            ScenarioRules.requireDomain(domain);
            ScenarioRules.requireSitePath(path);
            ScenarioRules.requireTitle(title);
        }

        @Override
        public String kind() {
            return "create_site";
        }
    }

    /** A request's site, looked up from its host and raw path. */
    record Lookup(String host, String path) implements ScenarioOp {
        public Lookup {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(path, "path");
            ScenarioRules.requireHost(host);
            ScenarioRules.requireLookupPath(path);
        }

        @Override
        public String kind() {
            return "lookup";
        }
    }

    /** One status flag (or the public value, true meaning 1) of a created site. */
    record SetFlag(int site, Flag flag, boolean value) implements ScenarioOp {
        public SetFlag {
            Objects.requireNonNull(flag, "flag");
            if (site < 1) {
                throw new IllegalArgumentException("a scenario never changes the main site's flags: QuarkPress refuses"
                        + " it and WordPress's API does not");
            }
        }

        @Override
        public String kind() {
            return "set_flag";
        }
    }

    /** Default options of a site. */
    record ReadOptions(int site, List<String> names) implements ScenarioOp {
        public ReadOptions {
            names = List.copyOf(names);
            if (site < 0) {
                throw new IllegalArgumentException("a scenario site reference is 0 or more: " + site);
            }
            if (names.isEmpty()) {
                throw new IllegalArgumentException("read_options names at least one option");
            }
            names.forEach(ScenarioRules::requireOption);
        }

        @Override
        public String kind() {
            return "read_options";
        }
    }

    /**
     * A user registering with {@code username} and, when not empty, given and family names (WordPress's first and last
     * name). On both sides a new user is a member of no site.
     */
    record CreateUser(String username, String givenName, String familyName) implements ScenarioOp {
        public CreateUser {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(givenName, "givenName");
            Objects.requireNonNull(familyName, "familyName");
            ScenarioRules.requireName(givenName);
            ScenarioRules.requireName(familyName);
            ScenarioRules.requireUsername(username, givenName.isEmpty() && familyName.isEmpty());
        }

        @Override
        public String kind() {
            return "create_user";
        }
    }

    /** A user added to a site with a role (empty: a member without role). */
    record AddUser(int user, int site, String role) implements ScenarioOp {
        public AddUser {
            Objects.requireNonNull(role, "role");
            ScenarioRules.requireKey(role, true);
        }

        @Override
        public String kind() {
            return "add_user";
        }
    }

    /** A user removed from a site. */
    record RemoveUser(int user, int site) implements ScenarioOp {
        @Override
        public String kind() {
            return "remove_user";
        }
    }

    /** A user's roles on a site replaced by one role (empty: none). */
    record SetRole(int user, int site, String role) implements ScenarioOp {
        public SetRole {
            Objects.requireNonNull(role, "role");
            ScenarioRules.requireKey(role, true);
        }

        @Override
        public String kind() {
            return "set_role";
        }
    }

    /** An individual capability of a user on a site (false: a denial). */
    record AddCap(int user, int site, String capability, boolean grant) implements ScenarioOp {
        public AddCap {
            Objects.requireNonNull(capability, "capability");
            ScenarioRules.requireKey(capability, false);
        }

        @Override
        public String kind() {
            return "add_cap";
        }
    }

    /** A user made a super admin, or no longer one. */
    record SuperAdmin(int user, boolean grant) implements ScenarioOp {
        @Override
        public String kind() {
            return "super_admin";
        }
    }

    /** A network option: {@code add_new_users}, or {@code menu_plugins} (the plugins entry of menu_items). */
    record NetworkFlag(String name, boolean value) implements ScenarioOp {
        public NetworkFlag {
            Objects.requireNonNull(name, "name");
            ScenarioRules.requireNetworkFlag(name);
        }

        @Override
        public String kind() {
            return "network_flag";
        }
    }

    /** A site option that capability rules read: {@code link_manager_enabled}. */
    record SiteSetting(int site, String name, boolean value) implements ScenarioOp {
        public SiteSetting {
            Objects.requireNonNull(name, "name");
            ScenarioRules.requireSiteSetting(name);
        }

        @Override
        public String kind() {
            return "site_setting";
        }
    }

    /** A role added to a site's table, granting the listed capabilities. */
    record AddRole(int site, String role, String name, List<String> capabilities) implements ScenarioOp {
        public AddRole {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(name, "name");
            capabilities = List.copyOf(capabilities);
            ScenarioRules.requireKey(role, true);
            ScenarioRules.requireName(name);
            capabilities.forEach(capability -> ScenarioRules.requireKey(capability, false));
        }

        @Override
        public String kind() {
            return "add_role";
        }
    }

    /** A capability of a site's role set (false: a denial). */
    record RoleCap(int site, String role, String capability, boolean grant) implements ScenarioOp {
        public RoleCap {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(capability, "capability");
            ScenarioRules.requireKey(role, false);
            ScenarioRules.requireKey(capability, false);
        }

        @Override
        public String kind() {
            return "role_cap";
        }
    }

    /** A role removed from a site's table. */
    record RemoveRole(int site, String role) implements ScenarioOp {
        public RemoveRole {
            Objects.requireNonNull(role, "role");
            ScenarioRules.requireKey(role, false);
        }

        @Override
        public String kind() {
            return "remove_role";
        }
    }

    /**
     * Whether a user ({@code 0}: a logged-out visitor) has a capability on a site, checked against another user when
     * {@code target} is not 0.
     */
    record Can(int user, int site, String capability, int target) implements ScenarioOp {
        public Can {
            Objects.requireNonNull(capability, "capability");
            ScenarioRules.requireKey(capability, false);
            if (user < 0 || target < 0) {
                throw new IllegalArgumentException("a scenario user reference is 0 or more");
            }
        }

        @Override
        public String kind() {
            return "can";
        }
    }

    /** The site fields a scenario may set. */
    enum Flag {
        ARCHIVED,
        MATURE,
        SPAM,
        DELETED,
        PUBLIC;

        /** The column name in WordPress's sites table. */
        public String wordpressName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Flag named(String name) {
            for (Flag flag : values()) {
                if (flag.wordpressName().equals(name)) {
                    return flag;
                }
            }
            throw new IllegalArgumentException("unknown site flag '" + name + "'");
        }
    }
}
