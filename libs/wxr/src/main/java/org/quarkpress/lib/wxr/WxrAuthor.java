package org.quarkpress.lib.wxr;

import java.util.Map;

/** A {@code wp:author} block (WXR 1.1+). {@code id} is null when absent or not a whole number. */
public record WxrAuthor(
        Long id,
        String login,
        String email,
        String displayName,
        String firstName,
        String lastName,
        Map<String, String> rawFields)
        implements WxrEntry {

    public WxrAuthor {
        rawFields = Copies.map(rawFields);
    }

    public WxrAuthor(Long id, String login, String email, String displayName, String firstName, String lastName) {
        this(id, login, email, displayName, firstName, lastName, Map.of());
    }
}
