package org.quarkpress.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A registered post type: WordPress's {@code register_post_type} result for the arguments QuarkPress uses. Build one
 * with {@link #builder(String)}, which applies WordPress's defaults: flags not given follow {@code public}, the
 * meta-capability mapping defaults to on for the {@code post} and {@code page} capability types, {@code supports}
 * defaults to title, editor and autosave (also when given empty; {@link Builder#noSupports()} gives none), and labels
 * are derived from the names.
 */
public record PostTypeDefinition(
        String key,
        Map<String, String> labels,
        String description,
        boolean publicly,
        boolean hierarchical,
        boolean excludeFromSearch,
        boolean publiclyQueryable,
        boolean embeddable,
        boolean showUi,
        boolean showInMenu,
        boolean showInNavMenus,
        boolean showInAdminBar,
        String capabilityType,
        String capabilityTypePlural,
        Map<String, String> capabilities,
        boolean mapMetaCap,
        List<String> supports,
        boolean hasArchive,
        boolean showInRest,
        Optional<String> restBase,
        boolean builtIn) {

    /** The longest post type key WordPress accepts. */
    public static final int MAX_KEY_LENGTH = 20;

    public PostTypeDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(capabilityType, "capabilityType");
        Objects.requireNonNull(capabilityTypePlural, "capabilityTypePlural");
        Objects.requireNonNull(restBase, "restBase");
        if (key.isEmpty()
                || key.length() > MAX_KEY_LENGTH
                || !WordPressKeys.sanitize(key).equals(key)) {
            throw new IllegalArgumentException("a post type key holds 1 to 20 of a-z, 0-9, _ and -: '" + key + "'");
        }
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(labels, "labels")));
        capabilities =
                Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(capabilities, "capabilities")));
        supports = List.copyOf(supports);
    }

    /** Whether the type supports {@code feature} (WordPress's {@code post_type_supports}). */
    public boolean supports(String feature) {
        return supports.contains(feature);
    }

    /** A builder for the type registered as {@code name} (sanitized as a key when built). */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** The arguments of a registration; anything not given takes WordPress's default when {@link #build()} runs. */
    public static final class Builder {

        private final String name;
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final Map<String, String> capabilities = new LinkedHashMap<>();
        private String label;
        private String description = "";
        private Boolean publicly;
        private Boolean hierarchical;
        private Boolean excludeFromSearch;
        private Boolean publiclyQueryable;
        private Boolean embeddable;
        private Boolean showUi;
        private Boolean showInMenu;
        private Boolean showInNavMenus;
        private Boolean showInAdminBar;
        private Boolean mapMetaCap;
        private String capabilityType = "post";
        private String capabilityTypePlural;
        private List<String> supports;
        private boolean noSupports;
        private boolean hasArchive;
        private boolean showInRest;
        private String restBase;
        private boolean builtIn;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** WordPress's {@code label} argument: the name label when the labels give none. */
        public Builder label(String label) {
            this.label = Objects.requireNonNull(label, "label");
            return this;
        }

        public Builder labels(Map<String, String> labels) {
            Objects.requireNonNull(labels, "labels")
                    .forEach((key, value) -> this.labels.put(
                            Objects.requireNonNull(key, "label key"), Objects.requireNonNull(value, "label value")));
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

        public Builder hierarchical(boolean value) {
            this.hierarchical = value;
            return this;
        }

        public Builder excludeFromSearch(boolean value) {
            this.excludeFromSearch = value;
            return this;
        }

        public Builder publiclyQueryable(boolean value) {
            this.publiclyQueryable = value;
            return this;
        }

        public Builder embeddable(boolean value) {
            this.embeddable = value;
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

        public Builder showInAdminBar(boolean value) {
            this.showInAdminBar = value;
            return this;
        }

        /** The capability type as one name; the plural adds "s". */
        public Builder capabilityType(String singular) {
            this.capabilityType = Objects.requireNonNull(singular, "singular");
            this.capabilityTypePlural = null;
            return this;
        }

        /** The capability type as WordPress's two-name form; the mapping then defaults to off. */
        public Builder capabilityType(String singular, String plural) {
            this.capabilityType = Objects.requireNonNull(singular, "singular");
            this.capabilityTypePlural = Objects.requireNonNull(plural, "plural");
            return this;
        }

        /** Capability names that replace the computed ones (WordPress's names as keys, such as create_posts). */
        public Builder capabilities(Map<String, String> capabilities) {
            Objects.requireNonNull(capabilities, "capabilities")
                    .forEach((key, value) -> this.capabilities.put(
                            Objects.requireNonNull(key, "capability key"),
                            Objects.requireNonNull(value, "capability")));
            return this;
        }

        public Builder mapMetaCap(boolean value) {
            this.mapMetaCap = value;
            return this;
        }

        /** The supported features; an empty list means WordPress's default, as in WordPress. */
        public Builder supports(List<String> features) {
            this.supports = List.copyOf(features);
            this.noSupports = false;
            return this;
        }

        /** No feature at all (WordPress's {@code supports => false}). */
        public Builder noSupports() {
            this.supports = null;
            this.noSupports = true;
            return this;
        }

        public Builder hasArchive(boolean value) {
            this.hasArchive = value;
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

        /** Marks a type the posts module registers itself. */
        public Builder builtIn() {
            this.builtIn = true;
            return this;
        }

        public PostTypeDefinition build() {
            String key = WordPressKeys.sanitize(name);
            if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
                throw new IllegalArgumentException(
                        "post type '" + name + "': Post type names must be between 1 and 20 characters in length.");
            }
            boolean pub = Boolean.TRUE.equals(publicly);
            boolean hier = Boolean.TRUE.equals(hierarchical);
            boolean ui = showUi != null ? showUi : pub;
            boolean menu = showInMenu == null || !ui ? ui : showInMenu;
            boolean map = mapMetaCap != null
                    ? mapMetaCap
                    : capabilities.isEmpty()
                            && capabilityTypePlural == null
                            && (capabilityType.equals("post") || capabilityType.equals("page"));
            return new PostTypeDefinition(
                    key,
                    PostTypeLabels.of(key, hier, Optional.ofNullable(label), labels),
                    description,
                    pub,
                    hier,
                    excludeFromSearch != null ? excludeFromSearch : !pub,
                    publiclyQueryable != null ? publiclyQueryable : pub,
                    embeddable != null ? embeddable : pub,
                    ui,
                    menu,
                    showInNavMenus != null ? showInNavMenus : pub,
                    showInAdminBar != null ? showInAdminBar : menu,
                    capabilityType,
                    capabilityTypePlural != null ? capabilityTypePlural : capabilityType + "s",
                    capabilities,
                    map,
                    supportsList(),
                    hasArchive,
                    showInRest,
                    Optional.ofNullable(restBase),
                    builtIn);
        }

        private List<String> supportsList() {
            if (noSupports) {
                return List.of();
            }
            if (supports == null || supports.isEmpty()) {
                return List.of("title", "editor", "autosave");
            }
            Set<String> features = new LinkedHashSet<>();
            for (String feature : supports) {
                features.add(Objects.requireNonNull(feature, "feature"));
            }
            List<String> result = new ArrayList<>(features);
            if (features.contains("editor") && !features.contains("autosave")) {
                result.add("autosave");
            }
            return result;
        }
    }
}
