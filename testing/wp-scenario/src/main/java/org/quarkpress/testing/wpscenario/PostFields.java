package org.quarkpress.testing.wpscenario;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The fields an insert or update gives (absent: not given). {@code parent} and {@code author} are references: a
 * post's insertion index and a user's creation index, 0 for none. Dates are WordPress's {@code Y-m-d H:i:s} text.
 */
public record PostFields(
        Optional<String> type,
        Optional<String> status,
        Optional<String> title,
        Optional<String> content,
        Optional<String> excerpt,
        Optional<String> slug,
        Optional<String> password,
        Optional<String> commentStatus,
        Optional<String> pingStatus,
        Optional<String> date,
        Optional<String> dateGmt,
        OptionalInt parent,
        OptionalInt author,
        OptionalInt menuOrder) {

    /** The keys of the scenario format. */
    static final List<String> KEYS = List.of(
            "type",
            "status",
            "title",
            "content",
            "excerpt",
            "slug",
            "password",
            "comment_status",
            "ping_status",
            "date",
            "date_gmt",
            "parent",
            "author",
            "menu_order");

    public PostFields {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(excerpt, "excerpt");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(commentStatus, "commentStatus");
        Objects.requireNonNull(pingStatus, "pingStatus");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(dateGmt, "dateGmt");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(menuOrder, "menuOrder");
        for (Optional<String> text :
                List.of(type, status, title, content, excerpt, slug, password, commentStatus, pingStatus)) {
            text.ifPresent(ScenarioRules::requirePostText);
        }
        slug.ifPresent(ScenarioRules::requirePostSlug);
        date.ifPresent(ScenarioRules::requirePostDate);
        dateGmt.ifPresent(ScenarioRules::requirePostDate);
        parent.ifPresent(ScenarioRules::requireReference);
        author.ifPresent(ScenarioRules::requireReference);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String type;
        private String status;
        private String title;
        private String content;
        private String excerpt;
        private String slug;
        private String password;
        private String commentStatus;
        private String pingStatus;
        private String date;
        private String dateGmt;
        private OptionalInt parent = OptionalInt.empty();
        private OptionalInt author = OptionalInt.empty();
        private OptionalInt menuOrder = OptionalInt.empty();

        private Builder() {}

        public Builder type(String value) {
            type = value;
            return this;
        }

        public Builder status(String value) {
            status = value;
            return this;
        }

        public Builder title(String value) {
            title = value;
            return this;
        }

        public Builder content(String value) {
            content = value;
            return this;
        }

        public Builder excerpt(String value) {
            excerpt = value;
            return this;
        }

        public Builder slug(String value) {
            slug = value;
            return this;
        }

        public Builder password(String value) {
            password = value;
            return this;
        }

        public Builder commentStatus(String value) {
            commentStatus = value;
            return this;
        }

        public Builder pingStatus(String value) {
            pingStatus = value;
            return this;
        }

        public Builder date(String value) {
            date = value;
            return this;
        }

        public Builder dateGmt(String value) {
            dateGmt = value;
            return this;
        }

        public Builder parent(int value) {
            parent = OptionalInt.of(value);
            return this;
        }

        public Builder author(int value) {
            author = OptionalInt.of(value);
            return this;
        }

        public Builder menuOrder(int value) {
            menuOrder = OptionalInt.of(value);
            return this;
        }

        public PostFields build() {
            return new PostFields(
                    Optional.ofNullable(type),
                    Optional.ofNullable(status),
                    Optional.ofNullable(title),
                    Optional.ofNullable(content),
                    Optional.ofNullable(excerpt),
                    Optional.ofNullable(slug),
                    Optional.ofNullable(password),
                    Optional.ofNullable(commentStatus),
                    Optional.ofNullable(pingStatus),
                    Optional.ofNullable(date),
                    Optional.ofNullable(dateGmt),
                    parent,
                    author,
                    menuOrder);
        }
    }
}
