package org.quarkword.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A registered taxonomy: WordPress's {@code register_taxonomy} result for the arguments QuarkWord uses. Build one with
 * {@link #builder(String, List)}, which applies WordPress's defaults: a taxonomy is public unless told otherwise, the
 * interface flags follow {@code public} and each other as WordPress derives them, the four capability names default to
 * {@code manage_categories} (managing, editing and deleting terms) and {@code edit_posts} (assigning them), and labels
 * are derived from the names. The key is not sanitized; it must be 1 to 32 bytes long and not "0" (which PHP reads as
 * empty). A default term whose name is "" or "0" is no default term, as WordPress ignores an empty one. The query
 * variable and the rewrite arguments follow WordPress's registration: a publicly queryable taxonomy is queried by its
 * key unless told otherwise, and a public taxonomy gets rewrite rules under its key unless told otherwise. A given
 * query variable is kept as given; the taxonomy module slugifies it when it registers the taxonomy
 * ({@link #withQueryVar(String)}). Meta boxes are not kept.
 */
public record TaxonomyDefinition(
        String key,
        List<String> objectTypes,
        Map<String, String> labels,
        String description,
        boolean publicly,
        boolean publiclyQueryable,
        boolean hierarchical,
        boolean showUi,
        boolean showInMenu,
        boolean showInNavMenus,
        boolean showTagcloud,
        boolean showInQuickEdit,
        boolean showAdminColumn,
        Map<String, String> capabilities,
        boolean showInRest,
        Optional<String> restBase,
        Optional<DefaultTerm> defaultTerm,
        boolean sort,
        Optional<String> queryVar,
        Optional<Rewrite> rewrite,
        boolean builtIn) {

    /** The longest taxonomy name WordPress accepts, in bytes of UTF-8. */
    public static final int MAX_KEY_BYTES = 32;

    /**
     * WordPress's {@code rewrite} arguments: the slug term links start with, whether they go under the permalink
     * structure's front, and whether a term's link holds its ancestors' slugs.
     */
    public record Rewrite(String slug, boolean withFront, boolean hierarchical) {
        public Rewrite {
            Objects.requireNonNull(slug, "slug");
            if (slug.isEmpty()) {
                throw new IllegalArgumentException("a rewrite slug is not empty");
            }
        }
    }

    /** The four capability names every taxonomy has, in WordPress's order. */
    public static final List<String> CAPABILITY_KEYS =
            List.of("manage_terms", "edit_terms", "delete_terms", "assign_terms");

    /** The term every post of an attached type gets when it has none of this taxonomy. */
    public record DefaultTerm(String name, String slug, String description) {
        public DefaultTerm {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(description, "description");
        }
    }

    public TaxonomyDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(restBase, "restBase");
        Objects.requireNonNull(defaultTerm, "defaultTerm");
        Objects.requireNonNull(queryVar, "queryVar");
        Objects.requireNonNull(rewrite, "rewrite");
        requireKey(key);
        if (queryVar.filter(String::isEmpty).isPresent()) {
            throw new IllegalArgumentException("taxonomy '" + key + "': a query variable is not empty");
        }
        defaultTerm = defaultTerm.filter(term -> !empty(term.name()));
        objectTypes = List.copyOf(new LinkedHashSet<>(objectTypes));
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(labels, "labels")));
        capabilities =
                Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(capabilities, "capabilities")));
        for (String name : CAPABILITY_KEYS) {
            if (!capabilities.containsKey(name)) {
                throw new IllegalArgumentException("taxonomy '" + key + "' lacks the capability name " + name);
            }
        }
    }

    /** This definition with {@code queryVar} as its query variable (not empty). */
    public TaxonomyDefinition withQueryVar(String queryVar) {
        return new TaxonomyDefinition(
                key,
                objectTypes,
                labels,
                description,
                publicly,
                publiclyQueryable,
                hierarchical,
                showUi,
                showInMenu,
                showInNavMenus,
                showTagcloud,
                showInQuickEdit,
                showAdminColumn,
                capabilities,
                showInRest,
                restBase,
                defaultTerm,
                sort,
                Optional.of(Objects.requireNonNull(queryVar, "queryVar")),
                rewrite,
                builtIn);
    }

    /**
     * The capability name for {@code manage_terms}, {@code edit_terms}, {@code delete_terms} or
     * {@code assign_terms}.
     */
    public String capability(String name) {
        if (!CAPABILITY_KEYS.contains(name)) {
            throw new IllegalArgumentException("not a taxonomy capability: " + name);
        }
        return capabilities.get(name);
    }

    /** Whether posts of {@code type} take terms of this taxonomy (WordPress's {@code is_object_in_taxonomy}). */
    public boolean attachedTo(String type) {
        return objectTypes.contains(type);
    }

    /** A builder for the taxonomy {@code key} attached to {@code objectTypes} (duplicates are kept once). */
    public static Builder builder(String key, List<String> objectTypes) {
        return new Builder(key, objectTypes);
    }

    /** PHP's {@code empty()} for text: "" and "0". */
    private static boolean empty(String text) {
        return text.isEmpty() || text.equals("0");
    }

    private static void requireKey(String key) {
        int bytes = key.getBytes(StandardCharsets.UTF_8).length;
        if (empty(key) || bytes > MAX_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "taxonomy '" + key + "': Taxonomy names must be between 1 and 32 characters in length.");
        }
    }

    /** The arguments of a registration; anything not given takes WordPress's default when {@link #build()} runs. */
    public static final class Builder {

        private final String key;
        private final List<String> objectTypes;
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final Map<String, String> capabilities = new LinkedHashMap<>();
        private String label;
        private String description = "";
        private boolean publicly = true;
        private Boolean publiclyQueryable;
        private boolean hierarchical;
        private Boolean showUi;
        private Boolean showInMenu;
        private Boolean showInNavMenus;
        private Boolean showTagcloud;
        private Boolean showInQuickEdit;
        private boolean showAdminColumn;
        private boolean showInRest;
        private String restBase;
        private DefaultTerm defaultTerm;
        private boolean sort;
        private String queryVar;
        private boolean noQueryVar;
        private Boolean rewriteOn;
        private String rewriteSlug;
        private boolean rewriteWithFront;
        private boolean rewriteHierarchical;
        private boolean builtIn;

        private Builder(String key, List<String> objectTypes) {
            this.key = Objects.requireNonNull(key, "key");
            this.objectTypes = new ArrayList<>();
            for (String type : Objects.requireNonNull(objectTypes, "objectTypes")) {
                this.objectTypes.add(Objects.requireNonNull(type, "object type"));
            }
        }

        /** WordPress's {@code label} argument: the name label when the labels give none. */
        public Builder label(String label) {
            this.label = Objects.requireNonNull(label, "label");
            return this;
        }

        public Builder labels(Map<String, String> labels) {
            Objects.requireNonNull(labels, "labels")
                    .forEach((name, value) -> this.labels.put(
                            Objects.requireNonNull(name, "label key"), Objects.requireNonNull(value, "label value")));
            return this;
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        public Builder publicly(boolean value) {
            this.publicly = value;
            return this;
        }

        public Builder publiclyQueryable(boolean value) {
            this.publiclyQueryable = value;
            return this;
        }

        public Builder hierarchical(boolean value) {
            this.hierarchical = value;
            return this;
        }

        public Builder showUi(boolean value) {
            this.showUi = value;
            return this;
        }

        public Builder showInMenu(boolean value) {
            this.showInMenu = value;
            return this;
        }

        public Builder showInNavMenus(boolean value) {
            this.showInNavMenus = value;
            return this;
        }

        public Builder showTagcloud(boolean value) {
            this.showTagcloud = value;
            return this;
        }

        public Builder showInQuickEdit(boolean value) {
            this.showInQuickEdit = value;
            return this;
        }

        public Builder showAdminColumn(boolean value) {
            this.showAdminColumn = value;
            return this;
        }

        /** Capability names that replace the defaults ({@code manage_terms}, ... as keys). */
        public Builder capabilities(Map<String, String> capabilities) {
            Objects.requireNonNull(capabilities, "capabilities")
                    .forEach((name, value) -> this.capabilities.put(
                            Objects.requireNonNull(name, "capability key"),
                            Objects.requireNonNull(value, "capability")));
            return this;
        }

        public Builder showInRest(boolean value) {
            this.showInRest = value;
            return this;
        }

        public Builder restBase(String restBase) {
            this.restBase = Objects.requireNonNull(restBase, "restBase");
            return this;
        }

        /**
         * A default term given by name only, as WordPress turns a text {@code default_term} into one; "" or "0" gives
         * none.
         */
        public Builder defaultTerm(String name) {
            return defaultTerm(name, "", "");
        }

        /** A default term; a name of "" or "0" gives none (and removes one given before). */
        public Builder defaultTerm(String name, String slug, String description) {
            DefaultTerm term = new DefaultTerm(name, slug, description);
            this.defaultTerm = empty(name) ? null : term;
            return this;
        }

        /** Whether assignments remember the order terms were given in (WordPress's {@code sort}). */
        public Builder sort(boolean value) {
            this.sort = value;
            return this;
        }

        /**
         * The query variable, kept as given (not empty); not given, a publicly queryable taxonomy is queried by its
         * key.
         */
        public Builder queryVar(String name) {
            if (Objects.requireNonNull(name, "name").isEmpty()) {
                throw new IllegalArgumentException("taxonomy '" + key + "': a query variable is not empty");
            }
            this.queryVar = name;
            this.noQueryVar = false;
            return this;
        }

        /** No query variable (WordPress's {@code query_var => false}). */
        public Builder noQueryVar() {
            this.queryVar = null;
            this.noQueryVar = true;
            return this;
        }

        /** The rewrite arguments; an empty slug means the key. */
        public Builder rewrite(String slug, boolean withFront, boolean hierarchical) {
            this.rewriteSlug = Objects.requireNonNull(slug, "slug");
            this.rewriteWithFront = withFront;
            this.rewriteHierarchical = hierarchical;
            this.rewriteOn = true;
            return this;
        }

        /** Rewrite rules with WordPress's defaults (true), or none at all (false). */
        public Builder rewrite(boolean on) {
            this.rewriteSlug = null;
            this.rewriteOn = on;
            return this;
        }

        /** Marks a taxonomy the taxonomy module registers itself. */
        public Builder builtIn() {
            this.builtIn = true;
            return this;
        }

        public TaxonomyDefinition build() {
            requireKey(key);
            boolean queryable = publiclyQueryable != null ? publiclyQueryable : publicly;
            boolean ui = showUi != null ? showUi : publicly;
            boolean menu = showInMenu == null || !ui ? ui : showInMenu;
            Map<String, String> caps = new LinkedHashMap<>();
            caps.put("manage_terms", "manage_categories");
            caps.put("edit_terms", "manage_categories");
            caps.put("delete_terms", "manage_categories");
            caps.put("assign_terms", "edit_posts");
            caps.putAll(capabilities);
            return new TaxonomyDefinition(
                    key,
                    objectTypes,
                    TaxonomyLabels.of(key, hierarchical, Optional.ofNullable(label), labels),
                    description,
                    publicly,
                    queryable,
                    hierarchical,
                    ui,
                    menu,
                    showInNavMenus != null ? showInNavMenus : publicly,
                    showTagcloud != null ? showTagcloud : ui,
                    showInQuickEdit != null ? showInQuickEdit : ui,
                    showAdminColumn,
                    caps,
                    showInRest,
                    Optional.ofNullable(restBase),
                    Optional.ofNullable(defaultTerm),
                    sort,
                    queryVar(queryable),
                    rewrite(),
                    builtIn);
        }

        private Optional<String> queryVar(boolean queryable) {
            if (noQueryVar || !queryable) {
                return Optional.empty();
            }
            return Optional.of(queryVar != null ? queryVar : key);
        }

        private Optional<Rewrite> rewrite() {
            if (Boolean.FALSE.equals(rewriteOn)) {
                return Optional.empty();
            }
            if (rewriteSlug != null) {
                return Optional.of(
                        new Rewrite(rewriteSlug.isEmpty() ? key : rewriteSlug, rewriteWithFront, rewriteHierarchical));
            }
            if (Boolean.TRUE.equals(rewriteOn) || publicly) {
                return Optional.of(new Rewrite(key, true, false));
            }
            return Optional.empty();
        }
    }
}
