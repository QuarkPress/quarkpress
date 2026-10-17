package org.quarkpress.lib.wpkses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * An independent reader of kses output: every element must be allowed, every attribute allowed on its element,
 * and every URI attribute's scheme (the text before the first ':' unless that text contains "/?") must be an
 * allowed protocol. It relies only on the canonical shape kses writes: {@code <name attr="value" ...>}.
 */
final class SafetyChecker {

    private final Allowlist allowlist;
    private final Set<String> uriAttributes;
    private final Set<String> protocols;

    SafetyChecker(Allowlist allowlist, List<String> uriAttributes, List<String> protocols) {
        this.allowlist = allowlist;
        this.uriAttributes = Set.copyOf(uriAttributes);
        this.protocols =
                Set.copyOf(protocols.stream().map(TextUtils::asciiLower).toList());
    }

    /** The problems found; empty for safe output. */
    List<String> problems(String html) {
        List<String> problems = new ArrayList<>();
        int i = html.indexOf('<');
        while (i >= 0) {
            int j = i + 1;
            boolean closing = j < html.length() && html.charAt(j) == '/';
            if (closing) {
                j++;
            }
            if (j >= html.length() || !TextUtils.isAsciiLetter(html.charAt(j))) {
                i = html.indexOf('<', i + 1);
                continue;
            }
            int k = j;
            while (k < html.length() && (TextUtils.isAsciiAlnum(html.charAt(k)) || html.charAt(k) == '-')) {
                k++;
            }
            String name = TextUtils.asciiLower(html.substring(j, k));
            ElementRule element = allowlist.elements().get(name);
            if (element == null) {
                problems.add("element <" + name + ">");
                i = html.indexOf('<', k);
                continue;
            }
            if (closing) {
                i = html.indexOf('<', k);
                continue;
            }
            int p = k;
            while (p < html.length() && html.charAt(p) != '>') {
                char c = html.charAt(p);
                if (c == ' ' || c == '/') {
                    p++;
                    continue;
                }
                int q = p;
                while (q < html.length() && " =>/".indexOf(html.charAt(q)) < 0) {
                    q++;
                }
                String attribute = html.substring(p, q);
                String value = null;
                if (q < html.length() && html.charAt(q) == '=') {
                    if (q + 1 >= html.length() || html.charAt(q + 1) != '"') {
                        problems.add("unquoted value of " + attribute);
                        break;
                    }
                    int end = html.indexOf('"', q + 2);
                    if (end < 0) {
                        problems.add("unterminated value of " + attribute);
                        break;
                    }
                    value = html.substring(q + 2, end);
                    q = end + 1;
                }
                boolean allowed = element.attributes().containsKey(attribute)
                        || (element.attributes().containsKey("data-*")
                                && AttributeChecker.isDataAttributeName(attribute));
                if (!allowed) {
                    problems.add("attribute " + name + "." + attribute);
                }
                if (value != null && uriAttributes.contains(attribute)) {
                    String decoded = value.replace("&quot;", "\"")
                            .replace("&apos;", "'")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&amp;", "&");
                    int colon = decoded.indexOf(':');
                    if (colon >= 0) {
                        String scheme = decoded.substring(0, colon);
                        if (!scheme.contains("/?") && !protocols.contains(scheme)) {
                            problems.add("scheme in " + attribute + "=\"" + decoded + "\"");
                        }
                    }
                }
                p = q;
            }
            i = html.indexOf('<', p);
        }
        return problems;
    }
}
