package org.quarkword.front.runtime;

import java.util.Objects;

/** A redirect the front end answers with: its status (3xx) and its {@code Location}, already sanitised. */
public record Redirect(int status, String location) {

    public Redirect {
        Objects.requireNonNull(location, "location");
        if (status < 300 || status > 399) {
            throw new IllegalArgumentException("a redirect's status is 3xx: " + status);
        }
    }

    /** A 301 to {@code url}, sanitised as WordPress's {@code wp_redirect} sanitises it. */
    public static Redirect permanent(String url) {
        return new Redirect(301, RedirectUrls.sanitize(url));
    }
}
