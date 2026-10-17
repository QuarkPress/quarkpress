package org.quarkpress.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * The options of one site, read through a per-request cache: all autoloaded options are read together the first time
 * one is needed. Names are trimmed the way WordPress trims them. Values are copies.
 */
public interface SiteOptions {

    Optional<JsonNode> get(String name);

    /**
     * A text, number or boolean value as PHP converts it to a string ({@code true} is "1", {@code false} is "",
     * {@code 5.0} is "5"); otherwise {@code fallback}.
     */
    String string(String name, String fallback);

    /** An integral number, or a text holding a decimal integer; otherwise {@code fallback}. */
    long number(String name, long fallback);

    /**
     * Truthiness as WordPress code sees option values: false for {@code false}, {@code 0}, {@code ""}, {@code "0"} and
     * empty arrays or objects, true for other values; {@code fallback} when the option does not exist.
     */
    boolean flag(String name, boolean fallback);
}
