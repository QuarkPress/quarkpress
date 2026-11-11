package org.quarkpress.outbound;

import java.time.Duration;
import java.util.Objects;

/**
 * The bounds of one fetch.
 *
 * @param timeout the whole exchange, redirects and body included; more than zero
 * @param maxRedirects how many redirects are followed; the 3xx answer after them is returned as is
 * @param maxBytes the most body bytes (after decompression) the response may have
 */
public record FetchLimits(Duration timeout, int maxRedirects, long maxBytes) {

    public FetchLimits {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("the timeout must be more than zero: " + timeout);
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("maxRedirects must not be negative: " + maxRedirects);
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative: " + maxBytes);
        }
    }
}
