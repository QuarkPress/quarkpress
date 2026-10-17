package org.quarkpress.lib.wpkses;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The attributes one allowed element may keep, by lowercase name. The key {@code data-*} also allows every
 * {@code data-} attribute whose name is ASCII lowercase letters, digits, '_' and '-', with the same rule. As in
 * WordPress, a rule under the exact name takes precedence over {@code data-*}. Following WordPress, a custom
 * allowlist whose {@code data-*} value is falsy (false, 0, "0", "", null, an empty array) allows no
 * {@code data-} attributes; in this typed model that is the absence of the {@code data-*} key.
 */
public final class ElementRule {

    private static final ElementRule NO_ATTRIBUTES = new ElementRule(Map.of(), true);

    private final Map<String, AttributeRule> attributes;
    private final boolean openingTags;

    ElementRule(Map<String, AttributeRule> attributes, boolean openingTags) {
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        this.openingTags = openingTags;
    }

    /** The element is allowed, but none of its attributes. */
    public static ElementRule noAttributes() {
        return NO_ATTRIBUTES;
    }

    /** The element with these attributes, in this order. */
    public static ElementRule of(Map<String, AttributeRule> attributes) {
        attributes.forEach((name, rule) -> {
            Objects.requireNonNull(name, "attribute name");
            Objects.requireNonNull(rule, "attribute rule");
        });
        return new ElementRule(attributes, true);
    }

    /**
     * WordPress's "entities" context used as an allowlist: its numeric keys keep closing tags such as
     * {@code </5>}, while a start tag makes PHP fail, which QuarkPress turns into removing it.
     */
    static ElementRule closingTagsOnly() {
        return new ElementRule(Map.of(), false);
    }

    public Map<String, AttributeRule> attributes() {
        return attributes;
    }

    public Optional<AttributeRule> attribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }

    /** A copy with {@code name} set to {@code rule}: replaced in place when present, else added at the end. */
    public ElementRule withAttribute(String name, AttributeRule rule) {
        Map<String, AttributeRule> copy = new LinkedHashMap<>(attributes);
        copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(rule, "rule"));
        return new ElementRule(copy, openingTags);
    }

    /** A copy without {@code name}. */
    public ElementRule withoutAttribute(String name) {
        Map<String, AttributeRule> copy = new LinkedHashMap<>(attributes);
        copy.remove(name);
        return new ElementRule(copy, openingTags);
    }

    boolean allowsOpeningTags() {
        return openingTags;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ElementRule other
                && openingTags == other.openingTags
                && attributes.equals(other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attributes, openingTags);
    }

    @Override
    public String toString() {
        return (openingTags ? "" : "closing tags only ") + attributes;
    }
}
