package org.quarkpress.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.quarkpress.core.OptionChanged;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.runtime.OptionNames;
import org.quarkpress.persistence.runtime.JsonValues;
import org.quarkpress.persistence.runtime.OptionWrites;

/**
 * Writes the current site's options; read them through {@code SiteContext.options()}. The methods behave like
 * WordPress's {@code update_option}, {@code add_option}, {@code delete_option} and {@code wp_set_option_autoload}.
 *
 * <p>Names are trimmed like WordPress trims them, and an empty name or {@code "0"} is refused. Values follow the rules
 * of {@link JsonValues}; numbers compare by value and object keys in order. A write that creates, changes or deletes a
 * value fires {@link OptionChanged} inside the transaction; an observer that throws rolls the transaction back. An
 * invalid name or value throws {@link IllegalArgumentException} without marking the caller's transaction for rollback.
 * The request's cached reads of the option are dropped on the write and again when the transaction completes.
 *
 * <p>Concurrent writes of one option never fail on its unique key: of several concurrent {@code add} calls exactly
 * one returns true, and concurrent {@code set} calls are applied one after the other. Writes lock the option's row,
 * never the site's row.
 *
 * <p>On MySQL, {@code add} of a name that already exists takes an exclusive lock on that option's row and the gap
 * before it until the transaction ends (PostgreSQL takes none), so it blocks other writers of that option and inserts
 * of neighbouring names of the same site until then.
 */
@ApplicationScoped
public class Options {

    @Inject
    SiteContext site;

    @Inject
    OptionWrites writes;

    /**
     * Sets a value, like {@code update_option}: a new option is autoloaded. Returns whether the value changed; an
     * unchanged value returns false and changes nothing.
     */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean set(String name, JsonNode value) {
        String normalized = OptionNames.normalize(name);
        JsonNode stored = JsonValues.requireStorable(value);
        return writes.set(site.siteId(), normalized, stored, null);
    }

    /**
     * Sets a value, like {@code update_option} with an autoload argument: a new option takes {@code autoload}, and so
     * does an existing one whose value changes. An unchanged value returns false and keeps the autoload flag; use
     * {@link #setAutoload} to change only the flag.
     */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean set(String name, JsonNode value, boolean autoload) {
        String normalized = OptionNames.normalize(name);
        JsonNode stored = JsonValues.requireStorable(value);
        return writes.set(site.siteId(), normalized, stored, autoload);
    }

    /** Adds an option, like {@code add_option}: returns false and changes nothing when the option already exists. */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean add(String name, JsonNode value, boolean autoload) {
        String normalized = OptionNames.normalize(name);
        JsonNode stored = JsonValues.requireStorable(value);
        return writes.add(site.siteId(), normalized, stored, autoload);
    }

    /**
     * Changes only the autoload flag, like {@code wp_set_option_autoload}. Returns whether it changed; false when the
     * option does not exist. Fires no event.
     */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean setAutoload(String name, boolean autoload) {
        String normalized = OptionNames.normalize(name);
        return writes.setAutoload(site.siteId(), normalized, autoload);
    }

    /** Deletes the option. Returns whether it existed. */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean delete(String name) {
        String normalized = OptionNames.normalize(name);
        return writes.delete(site.siteId(), normalized);
    }
}
