package org.quarkpress.lib.wpshortcode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, ordered map from shortcode name to handler. Order matters: when two names could match at the
 * same place, the earlier one wins (R3.3a). Re-adding a name replaces its handler and keeps its position.
 */
public final class ShortcodeRegistry {

    private final Map<String, ShortcodeHandler> handlers;
    private final List<String> names;

    private ShortcodeRegistry(Map<String, ShortcodeHandler> handlers) {
        this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(handlers));
        this.names = List.copyOf(handlers.keySet());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** WordPress's shortcode_exists: an exact, case-sensitive lookup. */
    public boolean contains(String name) {
        Objects.requireNonNull(name, "name");
        return handlers.containsKey(name);
    }

    public Optional<ShortcodeHandler> handler(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(handlers.get(name));
    }

    /** The names in registration order. */
    public List<String> names() {
        return names;
    }

    public int size() {
        return names.size();
    }

    public boolean isEmpty() {
        return names.isEmpty();
    }

    /** A builder that starts with this registry's entries. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.handlers.putAll(handlers);
        return builder;
    }

    /**
     * Rejects what WordPress's add_shortcode rejects: an empty name, or one containing a space, a control char
     * (U+0000 to U+0020) or any of {@code < > & / [ ] =}.
     */
    static void validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a shortcode name must not be empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Chars.endsName(c)) {
                String shown = c <= ' ' ? String.format("U+%04X", (int) c) : "'" + c + "'";
                throw new IllegalArgumentException("invalid shortcode name \"" + name + "\": it contains " + shown
                        + "; names may not contain spaces, control characters or any of < > & / [ ] =");
            }
        }
    }

    /** Collects handlers; {@link #build()} takes a snapshot. */
    public static final class Builder {
        private final LinkedHashMap<String, ShortcodeHandler> handlers = new LinkedHashMap<>();

        private Builder() {}

        /** Adds or replaces a handler (WordPress's add_shortcode). */
        public Builder add(String name, ShortcodeHandler handler) {
            validateName(name);
            handlers.put(name, Objects.requireNonNull(handler, "handler"));
            return this;
        }

        /** Removes a name if present (remove_shortcode). */
        public Builder remove(String name) {
            handlers.remove(Objects.requireNonNull(name, "name"));
            return this;
        }

        /** Removes every name (remove_all_shortcodes). */
        public Builder clear() {
            handlers.clear();
            return this;
        }

        public ShortcodeRegistry build() {
            return new ShortcodeRegistry(handlers);
        }
    }
}
