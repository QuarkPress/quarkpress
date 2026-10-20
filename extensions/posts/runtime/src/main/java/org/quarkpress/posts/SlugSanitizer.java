package org.quarkpress.posts;

/**
 * Turns a title or a given slug into a slug (WordPress's {@code sanitize_title} in its save context). The default
 * bean behaves as WordPress with the {@code en_US} locale; an application replaces it with its own bean. The result
 * holds only lower-case percent-encoded octets, {@code a-z}, {@code 0-9}, {@code _} and {@code -}, at most 200 bytes.
 */
public interface SlugSanitizer {

    String sanitize(String text, SlugContext context);
}
