package org.quarkpress.lib.wpkses;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Elements and attributes that kses keeps, keyed by name exactly as given. Input names are lowercased (ASCII)
 * before the lookup, so keys must be lowercase: a key with uppercase letters never matches. Immutable; build one
 * with {@link #builder()} or change a copy with {@link #toBuilder()}.
 */
public final class Allowlist implements AllowedHtml {

    private static final Allowlist EMPTY = new Allowlist(Map.of());

    private final Map<String, ElementRule> elements;

    private Allowlist(Map<String, ElementRule> elements) {
        this.elements = Collections.unmodifiableMap(new LinkedHashMap<>(elements));
    }

    /** Allows nothing: every tag is removed (WordPress's "strip" context). */
    public static Allowlist empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder(Map.of());
    }

    public Builder toBuilder() {
        return new Builder(elements);
    }

    public Map<String, ElementRule> elements() {
        return elements;
    }

    public Optional<ElementRule> element(String name) {
        return Optional.ofNullable(elements.get(name));
    }

    public boolean allows(String elementName) {
        return elements.containsKey(elementName);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Allowlist other && elements.equals(other.elements);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    public String toString() {
        return "Allowlist" + elements;
    }

    /** Collects elements and attributes in order; adding an existing name replaces it in place. */
    public static final class Builder {

        private final LinkedHashMap<String, ElementRule> elements;

        private Builder(Map<String, ElementRule> start) {
            this.elements = new LinkedHashMap<>(start);
        }

        /** Allows {@code name}; keeps its attributes when it is already allowed. */
        public Builder element(String name) {
            elements.putIfAbsent(Objects.requireNonNull(name, "name"), ElementRule.noAttributes());
            return this;
        }

        /** Allows {@code name} with exactly this rule, replacing any earlier rule for it. */
        public Builder element(String name, ElementRule rule) {
            elements.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(rule, "rule"));
            return this;
        }

        /** Allows {@code attribute} with any value on {@code element}, allowing the element if needed. */
        public Builder attribute(String element, String attribute) {
            return attribute(element, attribute, AttributeRule.allowed());
        }

        /** Allows {@code attribute} on {@code element} under {@code rule}, allowing the element if needed. */
        public Builder attribute(String element, String attribute, AttributeRule rule) {
            Objects.requireNonNull(element, "element");
            ElementRule current = elements.getOrDefault(element, ElementRule.noAttributes());
            elements.put(element, current.withAttribute(attribute, rule));
            return this;
        }

        /** Disallows {@code name} with all its attributes; no effect if it is not allowed. */
        public Builder removeElement(String name) {
            elements.remove(name);
            return this;
        }

        /** Disallows {@code attribute} on {@code element}; no effect if either is not allowed. */
        public Builder removeAttribute(String element, String attribute) {
            ElementRule current = elements.get(element);
            if (current != null) {
                elements.put(element, current.withoutAttribute(attribute));
            }
            return this;
        }

        /** The immutable allowlist collected so far. */
        public Allowlist build() {
            return elements.isEmpty() ? EMPTY : new Allowlist(elements);
        }
    }
}
