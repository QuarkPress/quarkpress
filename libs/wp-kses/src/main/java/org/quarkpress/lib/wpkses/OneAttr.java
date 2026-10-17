package org.quarkpress.lib.wpkses;

/** wp_kses_one_attr: sanitizes one "name=value" attribute text against the post allowlist. */
final class OneAttr {

    private OneAttr() {}

    /** WordPress's result: the attribute with its surrounding whitespace, or "" (plus that whitespace). */
    static String apply(String rawAttr, String element, Allowlist post, KsesConfig config) {
        String attr = NullFilter.clean(rawAttr, true);
        int lead = 0;
        while (lead < attr.length() && TextUtils.isPcreSpace(attr.charAt(lead))) {
            lead++;
        }
        int trail = attr.length();
        while (trail > 0 && TextUtils.isPcreSpace(attr.charAt(trail - 1))) {
            trail--;
        }
        // On an all-whitespace attribute the leading and trailing runs overlap, and WordPress keeps both.
        String leading = attr.substring(0, lead);
        String trailing = attr.substring(trail);
        String core = lead >= trail ? "" : attr.substring(lead, trail);

        String name;
        String value;
        String whole;
        boolean valueless;
        int eq = core.indexOf('=');
        if (eq < 0) {
            name = core;
            value = "";
            whole = core;
            valueless = true;
        } else {
            int nameEnd = eq;
            while (nameEnd > 0 && TextUtils.isPcreSpace(core.charAt(nameEnd - 1))) {
                nameEnd--;
            }
            int valueStart = eq + 1;
            while (valueStart < core.length() && TextUtils.isPcreSpace(core.charAt(valueStart))) {
                valueStart++;
            }
            name = core.substring(0, nameEnd);
            String raw = core.substring(valueStart);
            char quote = '"';
            if (!raw.isEmpty() && (raw.charAt(0) == '"' || raw.charAt(0) == '\'')) {
                quote = raw.charAt(0);
                if (raw.charAt(raw.length() - 1) != quote) {
                    return "";
                }
                raw = raw.length() >= 2 ? raw.substring(1, raw.length() - 1) : "";
            }
            value = HtmlEscaper.escape(raw);
            if (config.uriAttributes().contains(TextUtils.asciiLower(name))) {
                value = ProtocolFilter.filter(value, config.protocols());
            }
            whole = name + "=" + quote + value + quote;
            valueless = false;
        }
        ElementRule rule = post.elements().get(TextUtils.asciiLower(element));
        String kept = "";
        if (rule != null) {
            AttributeChecker.Result result =
                    AttributeChecker.check(name, value, whole, valueless, rule, config.checks());
            kept = result.accepted() ? result.whole() : "";
        }
        return leading + kept + trailing;
    }
}
