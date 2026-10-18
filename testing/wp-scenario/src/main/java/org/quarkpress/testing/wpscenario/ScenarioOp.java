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
