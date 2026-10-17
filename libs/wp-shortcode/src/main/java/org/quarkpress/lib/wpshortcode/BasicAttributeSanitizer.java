package org.quarkpress.lib.wpshortcode;

import java.util.Objects;
import java.util.Optional;

/**
 * The default {@link HtmlAttributeSanitizer}: WordPress's wp_kses_one_attr for the "post" context. Controls are
 * removed, surrounding whitespace is kept, the value is unquoted, escaped with esc_attr, stripped of disallowed
 * URL protocols on URI attributes, and requoted with its original quote; the attribute must be allowed for the
 * element by the "post" allowlist. Differences from WordPress: {@code style} attributes are rejected (no safe-CSS
 * filter until lib-wp-kses), and {@code object data} is rejected (WordPress accepts PDF URLs on the site's upload
 * host). lib-wp-kses will replace this class.
 */
public final class BasicAttributeSanitizer implements HtmlAttributeSanitizer {

    public static final BasicAttributeSanitizer INSTANCE = new BasicAttributeSanitizer();

    private BasicAttributeSanitizer() {}

    @Override
    public Optional<String> sanitize(String elementName, String attributeToken) {
        Objects.requireNonNull(elementName, "elementName");
        Objects.requireNonNull(attributeToken, "attributeToken");
        String token = Chars.removeControls(attributeToken);
        int lead = Chars.skipSpaces(token, 0, token.length());
        int trail = Chars.trimEnd(token, lead, token.length());
        String core = token.substring(lead, trail);
        String name;
        String value;
        boolean valueless;
        String rebuilt;
        int eq = core.indexOf('=');
        if (eq < 0) {
            name = core;
            value = "";
            valueless = true;
            rebuilt = core;
        } else {
            name = core.substring(0, Chars.trimEnd(core, 0, eq));
            String raw = core.substring(Chars.skipSpaces(core, eq + 1, core.length()));
            char quote = '"';
            if (!raw.isEmpty() && (raw.charAt(0) == '"' || raw.charAt(0) == '\'')) {
                quote = raw.charAt(0);
                if (raw.charAt(raw.length() - 1) != quote) {
                    return Optional.empty();
                }
                raw = raw.length() >= 2 ? raw.substring(1, raw.length() - 1) : "";
            }
            value = EscAttr.escape(raw);
            if (UrlProtocols.isUriAttribute(Chars.asciiLower(name))) {
                value = UrlProtocols.strip(value);
            }
            valueless = false;
            rebuilt = name + "=" + quote + value + quote;
        }
        if (!PostAllowlist.allows(elementName, name, value, valueless)) {
            rebuilt = "";
        }
        String result = token.substring(0, lead) + rebuilt + token.substring(trail);
        return Chars.isPhpBlank(result) ? Optional.empty() : Optional.of(result);
    }
}
