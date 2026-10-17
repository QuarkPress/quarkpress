package org.quarkpress.lib.wpkses;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** wp_kses_attr: rebuilds an allowed start tag from its accepted attributes. */
final class TagBuilder {

    private TagBuilder() {}

    static String build(String element, String attr, ElementRule rule, KsesRun run) {
        boolean selfClosing = endsWithSlash(attr);
        String slash = selfClosing ? " /" : "";
        if (rule.attributes().isEmpty()) {
            return "<" + element + slash + ">";
        }
        Set<String> missing = new LinkedHashSet<>();
        for (Map.Entry<String, AttributeRule> e : rule.attributes().entrySet()) {
            if (e.getValue().required()) {
                missing.add(e.getKey());
            }
        }
        Set<String> required = Set.copyOf(missing);
        // A start tag that loses a required attribute keeps its bare name, since its end tag stays anyway;
        // a self-closing one disappears.
        String stripped = selfClosing ? "" : "<" + element + ">";
        KsesConfig config = run.config();
        StringBuilder kept = new StringBuilder();
        List<HairAttribute> attributes = AttributeParser.parse(attr);
        for (HairAttribute a : attributes) {
            String value = a.value();
            if (value != null && config.uriAttributes().contains(a.name())) {
                value = ProtocolFilter.filter(value, run.protocols());
            }
            String recoded = value == null ? "" : AttributeParser.reencode(value);
            String whole = value == null ? a.name() : a.name() + "=\"" + recoded + "\"";
            AttributeChecker.Result result =
                    AttributeChecker.check(a.name(), recoded, whole, value == null, rule, config.checks());
            if (result.accepted()) {
                kept.append(' ').append(result.whole());
                missing.remove(a.name());
            } else if (required.contains(a.name())) {
                return stripped;
            }
        }
        if (!missing.isEmpty()) {
            return stripped;
        }
        return "<" + element + withoutAngleBrackets(kept) + slash + ">";
    }

    /** Whether the attribute text ends with '/', allowing whitespace around it. */
    static boolean endsWithSlash(String attr) {
        int end = attr.length();
        while (end > 0 && TextUtils.isPcreSpace(attr.charAt(end - 1))) {
            end--;
        }
        return end > 0 && attr.charAt(end - 1) == '/';
    }

    private static String withoutAngleBrackets(CharSequence s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '<' && c != '>') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
