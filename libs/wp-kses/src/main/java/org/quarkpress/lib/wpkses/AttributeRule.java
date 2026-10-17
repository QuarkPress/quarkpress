package org.quarkpress.lib.wpkses;

import java.util.List;
import java.util.Objects;

/**
 * How one attribute of an allowed element is checked: allowed as is, or with value checks, and optionally required
 * (a start tag without an accepted required attribute loses all its attributes). An attribute that is not allowed
 * has no rule at all.
 */
public final class AttributeRule {

    private static final AttributeRule ALLOWED = new AttributeRule(false, List.of());

    private final boolean required;
    private final List<AttributeCheck> checks;

    private AttributeRule(boolean required, List<AttributeCheck> checks) {
        this.required = required;
        this.checks = List.copyOf(checks);
    }

    /** Allowed with any value (WordPress's {@code true}). */
    public static AttributeRule allowed() {
        return ALLOWED;
    }

    /**
     * Allowed when every check passes, in this order. With no checks the attribute is allowed with any value, unlike
     * PHP's falsy {@code []} (for example on {@code data-*}), which WordPress treats as not allowed.
     */
    public static AttributeRule of(AttributeCheck... checks) {
        return new AttributeRule(false, List.of(checks));
    }

    /** Required, and allowed when every check passes. */
    public static AttributeRule required(AttributeCheck... checks) {
        return new AttributeRule(true, List.of(checks));
    }

    public boolean required() {
        return required;
    }

    public List<AttributeCheck> checks() {
        return checks;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AttributeRule other && required == other.required && checks.equals(other.checks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(required, checks);
    }

    @Override
    public String toString() {
        return required || !checks.isEmpty()
                ? "AttributeRule[required=" + required + ", checks=" + checks + "]"
                : "allowed";
    }
}
