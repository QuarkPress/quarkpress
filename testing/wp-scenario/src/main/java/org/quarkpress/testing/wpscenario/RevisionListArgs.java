package org.quarkpress.testing.wpscenario;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The arguments of a revision listing (absent: not given). {@code author} is a created user's index (1 or more);
 * {@code offset} only goes with {@code number}.
 */
public record RevisionListArgs(
        Optional<String> order,
        OptionalInt number,
        OptionalInt offset,
        OptionalInt author,
        Optional<String> autosaves,
        Optional<Boolean> checkEnabled) {

    /** The keys of the scenario format. */
    static final List<String> KEYS = List.of("order", "number", "offset", "author", "autosaves", "check_enabled");

    public RevisionListArgs {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(autosaves, "autosaves");
        Objects.requireNonNull(checkEnabled, "checkEnabled");
        order.ifPresent(value -> {
            if (!value.equals("asc") && !value.equals("desc")) {
                throw new IllegalArgumentException("a scenario lists revisions in asc or desc order: " + value);
            }
        });
        number.ifPresent(value -> {
            if (value < 1) {
                throw new IllegalArgumentException("a scenario lists at least 1 revision: " + value);
            }
        });
        offset.ifPresent(value -> {
            if (value < 0) {
                throw new IllegalArgumentException("a scenario lists revisions from an offset of 0 or more: " + value);
            }
            if (number.isEmpty()) {
                throw new IllegalArgumentException("a scenario lists revisions with an offset only with a number");
            }
        });
        author.ifPresent(value -> {
            ScenarioRules.requireReference(value);
            if (value == 0) {
                throw new IllegalArgumentException(
                        "a scenario lists revisions of a created user: author 0 is no filter");
            }
        });
        autosaves.ifPresent(value -> {
            if (!ScenarioRules.AUTOSAVE_MODES.contains(value)) {
                throw new IllegalArgumentException("a scenario lists autosaves as include, only or exclude: " + value);
            }
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String order;
        private OptionalInt number = OptionalInt.empty();
        private OptionalInt offset = OptionalInt.empty();
        private OptionalInt author = OptionalInt.empty();
        private String autosaves;
        private Boolean checkEnabled;

        private Builder() {}

        public Builder order(String value) {
            order = value;
            return this;
        }

        public Builder number(int value) {
            number = OptionalInt.of(value);
            return this;
        }

        public Builder offset(int value) {
            offset = OptionalInt.of(value);
            return this;
        }

        public Builder author(int value) {
            author = OptionalInt.of(value);
            return this;
        }

        public Builder autosaves(String value) {
            autosaves = value;
            return this;
        }

        public Builder checkEnabled(boolean value) {
            checkEnabled = value;
            return this;
        }

        public RevisionListArgs build() {
            return new RevisionListArgs(
                    Optional.ofNullable(order),
                    number,
                    offset,
                    author,
                    Optional.ofNullable(autosaves),
                    Optional.ofNullable(checkEnabled));
        }
    }
}
