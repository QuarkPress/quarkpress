package org.quarkpress.core;

import java.util.Objects;

/**
 * A registered post status: WordPress's {@code register_post_status} result. Build one with {@link #builder(String)},
 * which applies WordPress's defaults: a status given none of public, protected, private and internal is internal;
 * the remaining flags follow from those four; the label defaults to the name. A name over {@link #MAX_NAME_LENGTH}
 * characters is refused at registration, where WordPress only fails it when a post is saved.
 */
public record PostStatusDefinition(
        String name,
        String label,
        String labelCountSingular,
        String labelCountPlural,
        boolean publicly,
        boolean protectedStatus,
        boolean privateStatus,
        boolean internal,
        boolean publiclyQueryable,
        boolean excludeFromSearch,
        boolean showInAdminAllList,
        boolean showInAdminStatusList,
        boolean dateFloating,
        boolean builtIn) {

    /** The longest status name the post table stores. */
    public static final int MAX_NAME_LENGTH = 20;

    public PostStatusDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(labelCountSingular, "labelCountSingular");
        Objects.requireNonNull(labelCountPlural, "labelCountPlural");
        if (name.isEmpty()
                || name.length() > MAX_NAME_LENGTH
                || !WordPressKeys.sanitize(name).equals(name)) {
            throw new IllegalArgumentException(
                    "a post status name must hold 1 to 20 of a-z, 0-9, _ and -: '" + name + "'");
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** The arguments of a registration; anything not given takes WordPress's default when {@link #build()} runs. */
    public static final class Builder {

        private final String name;
        private String label;
        private String labelCountSingular;
        private String labelCountPlural;
        private Boolean publicly;
        private Boolean protectedStatus;
        private Boolean privateStatus;
        private Boolean internal;
        private Boolean publiclyQueryable;
        private Boolean excludeFromSearch;
        private Boolean showInAdminAllList;
        private Boolean showInAdminStatusList;
        private Boolean dateFloating;
        private boolean builtIn;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder label(String label) {
            this.label = Objects.requireNonNull(label, "label");
            return this;
        }

        public Builder labelCount(String singular, String plural) {
            this.labelCountSingular = Objects.requireNonNull(singular, "singular");
            this.labelCountPlural = Objects.requireNonNull(plural, "plural");
            return this;
        }

        public Builder publicly(boolean value) {
            this.publicly = value;
            return this;
        }

        public Builder protectedStatus(boolean value) {
            this.protectedStatus = value;
            return this;
        }

        public Builder privateStatus(boolean value) {
            this.privateStatus = value;
            return this;
        }

        public Builder internal(boolean value) {
            this.internal = value;
            return this;
        }

        public Builder publiclyQueryable(boolean value) {
            this.publiclyQueryable = value;
            return this;
        }

        public Builder excludeFromSearch(boolean value) {
            this.excludeFromSearch = value;
            return this;
        }

        public Builder showInAdminAllList(boolean value) {
            this.showInAdminAllList = value;
            return this;
        }

        public Builder showInAdminStatusList(boolean value) {
            this.showInAdminStatusList = value;
            return this;
        }

        public Builder dateFloating(boolean value) {
            this.dateFloating = value;
            return this;
        }

        public Builder builtIn() {
            this.builtIn = true;
            return this;
        }

        public PostStatusDefinition build() {
            String key = WordPressKeys.sanitize(name);
            if (key.isEmpty() || key.length() > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException(
                        "a post status name must hold 1 to 20 of a-z, 0-9, _ and -: '" + name + "'");
            }
            boolean noneGiven =
                    publicly == null && internal == null && protectedStatus == null && privateStatus == null;
            boolean isInternal = noneGiven || Boolean.TRUE.equals(internal);
            boolean isPublic = Boolean.TRUE.equals(publicly);
            String text = label != null ? label : key;
            return new PostStatusDefinition(
                    key,
                    text,
                    labelCountSingular != null ? labelCountSingular : text,
                    labelCountPlural != null ? labelCountPlural : text,
                    isPublic,
                    Boolean.TRUE.equals(protectedStatus),
                    Boolean.TRUE.equals(privateStatus),
                    isInternal,
                    publiclyQueryable != null ? publiclyQueryable : isPublic,
                    excludeFromSearch != null ? excludeFromSearch : isInternal,
                    showInAdminAllList != null ? showInAdminAllList : !isInternal,
                    showInAdminStatusList != null ? showInAdminStatusList : !isInternal,
                    Boolean.TRUE.equals(dateFloating),
                    builtIn);
        }
    }
}
