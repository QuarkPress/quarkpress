package org.quarkpress.lib.wpshortcode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** The elements and attributes of WordPress's "post" kses context, and the per-attribute value checks. */
final class PostAllowlist {

    private enum Kind {
        ANY,
        VALUELESS,
        PDF_OBJECT,
        VALUES
    }

    private record Rule(Kind kind, Set<String> values, String notation) {}

    /** One line per element: its name, then its own attributes; the "*" line holds the global attributes. */
    private static final String TABLE = """
            * aria-controls aria-current aria-describedby aria-details aria-expanded aria-hidden aria-label aria-labelledby aria-live class data-* dir hidden id lang role style tabindex title xml:lang
            address
            a download(valueless) href name rel rev target
            abbr
            acronym
            area alt coords href nohref shape target
            article align
            aside align
            audio autoplay controls loop muted preload src
            b
            bdo
            big
            blockquote cite
            br
            button aria-haspopup command commandfor disabled name popovertarget popovertargetaction type value
            caption align
            cite
            code
            col align char charoff span valign width
            colgroup align char charoff span valign width
            data value
            del datetime
            dd
            dfn
            details align name open
            div align popover
            dialog autofocus closedby open popover
            dl
            dt
            em
            fieldset
            figure align
            figcaption align
            font color face size
            footer align
            h1 align
            h2 align
            h3 align
            h4 align
            h5 align
            h6 align
            header align
            hgroup align
            hr align noshade size width
            i
            img align alt border height hspace loading longdesc src usemap vspace width
            ins cite datetime
            kbd
            label for
            legend align
            li align value
            main align
            map name
            mark
            menu type
            meter high low max min optimum value
            nav align
            object data(pdf-object) type(values:application/pdf)
            p align
            pre width
            progress max value
            q cite
            rb
            rp
            rt
            rtc
            ruby
            s
            samp
            search
            span align
            section align
            small
            strike
            strong
            sub
            summary align
            sup
            table align bgcolor border cellpadding cellspacing rules summary width
            tbody align char charoff valign
            td abbr align axis bgcolor char charoff colspan headers height nowrap rowspan scope valign width
            textarea cols disabled name readonly rows
            tfoot align char charoff valign
            th abbr align axis bgcolor char charoff colspan headers height nowrap rowspan scope valign width
            thead align char charoff valign
            time datetime
            title
            tr align bgcolor char charoff valign
            track default kind label src srclang
            tt
            u
            ul popover type
            ol reversed start type
            var
            video autoplay controls height loop muted playsinline poster preload src width
            wbr
            math display displaystyle mathbackground mathcolor mathsize scriptlevel
            mtext displaystyle mathbackground mathcolor mathsize scriptlevel
            mi displaystyle mathbackground mathcolor mathsize mathvariant scriptlevel
            mn displaystyle mathbackground mathcolor mathsize scriptlevel
            mo displaystyle fence form largeop lspace mathbackground mathcolor mathsize maxsize minsize movablelimits rspace scriptlevel separator stretchy symmetric
            mspace depth displaystyle height mathbackground mathcolor mathsize scriptlevel width
            ms displaystyle mathbackground mathcolor mathsize scriptlevel
            mrow displaystyle mathbackground mathcolor mathsize scriptlevel
            mfrac displaystyle linethickness mathbackground mathcolor mathsize scriptlevel
            msqrt displaystyle mathbackground mathcolor mathsize scriptlevel
            mroot displaystyle mathbackground mathcolor mathsize scriptlevel
            mstyle displaystyle mathbackground mathcolor mathsize scriptlevel
            merror displaystyle mathbackground mathcolor mathsize scriptlevel
            mpadded depth displaystyle height lspace mathbackground mathcolor mathsize scriptlevel voffset width
            mphantom displaystyle mathbackground mathcolor mathsize scriptlevel
            msub displaystyle mathbackground mathcolor mathsize scriptlevel
            msup displaystyle mathbackground mathcolor mathsize scriptlevel
            msubsup displaystyle mathbackground mathcolor mathsize scriptlevel
            munder accent accentunder displaystyle mathbackground mathcolor mathsize scriptlevel
            mover accent accentunder displaystyle mathbackground mathcolor mathsize scriptlevel
            munderover accent accentunder displaystyle mathbackground mathcolor mathsize scriptlevel
            mmultiscripts displaystyle mathbackground mathcolor mathsize scriptlevel
            mprescripts displaystyle mathbackground mathcolor mathsize scriptlevel
            mtable align columnalign columnlines columnspacing displaystyle frame framespacing mathbackground mathcolor mathsize rowalign rowlines rowspacing scriptlevel width
            mtr columnalign displaystyle mathbackground mathcolor mathsize rowalign scriptlevel
            mtd columnalign columnspan displaystyle mathbackground mathcolor mathsize rowalign rowspan scriptlevel
            semantics displaystyle mathbackground mathcolor mathsize scriptlevel
            annotation displaystyle encoding mathbackground mathcolor mathsize scriptlevel
            menclose displaystyle mathbackground mathcolor mathsize notation scriptlevel
            """;

    private static final Map<String, Map<String, Rule>> ELEMENTS = parse(TABLE);

    private PostAllowlist() {}

    /** True when {@code element} may carry {@code attribute} with this value under the "post" context. */
    static boolean allows(String element, String attribute, String value, boolean valueless) {
        Map<String, Rule> attributes = ELEMENTS.get(Chars.asciiLower(element));
        if (attributes == null) {
            return false;
        }
        String name = Chars.asciiLower(attribute);
        Rule rule = attributes.get(name);
        if (rule == null) {
            if (!isDataAttribute(name) || !attributes.containsKey("data-*")) {
                return false;
            }
            rule = attributes.get("data-*");
        }
        if (name.equals("style")) {
            return false; // safe-CSS filtering belongs to lib-wp-kses (difference D4)
        }
        return switch (rule.kind()) {
            case ANY -> true;
            case VALUELESS -> valueless;
            case PDF_OBJECT -> false; // needs the site's upload URL (difference D9)
            case VALUES -> rule.values().contains(Chars.asciiLower(value));
        };
    }

    /** element -> sorted rule notations, the same notation the parity driver writes for WordPress's table. */
    static SortedMap<String, List<String>> describe() {
        SortedMap<String, List<String>> out = new TreeMap<>();
        ELEMENTS.forEach((element, rules) -> {
            List<String> notations = new ArrayList<>();
            rules.values().forEach(rule -> notations.add(rule.notation()));
            Collections.sort(notations);
            out.put(element, List.copyOf(notations));
        });
        return Collections.unmodifiableSortedMap(out);
    }

    /** "data-" followed by one or more of a-z, 0-9, '_' and '-'. */
    private static boolean isDataAttribute(String lowercaseName) {
        if (!lowercaseName.startsWith("data-") || lowercaseName.length() == 5) {
            return false;
        }
        for (int i = 5; i < lowercaseName.length(); i++) {
            char c = lowercaseName.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Map<String, Rule>> parse(String table) {
        Map<String, Rule> global = new LinkedHashMap<>();
        Map<String, Map<String, Rule>> elements = new HashMap<>();
        for (String line : Chars.pieces(table, '\n')) {
            List<String> words = Chars.pieces(line, ' ');
            Map<String, Rule> rules = words.get(0).equals("*") ? global : new LinkedHashMap<>(global);
            for (int i = 1; i < words.size(); i++) {
                Rule rule = rule(words.get(i));
                String name = words.get(i).contains("(")
                        ? words.get(i).substring(0, words.get(i).indexOf('('))
                        : words.get(i);
                rules.put(name, rule);
            }
            if (!words.get(0).equals("*")) {
                elements.put(words.get(0), Map.copyOf(rules));
            }
        }
        return Map.copyOf(elements);
    }

    private static Rule rule(String notation) {
        if (notation.endsWith("(valueless)")) {
            return new Rule(Kind.VALUELESS, Set.of(), notation);
        }
        if (notation.endsWith("(pdf-object)")) {
            return new Rule(Kind.PDF_OBJECT, Set.of(), notation);
        }
        int values = notation.indexOf("(values:");
        if (values >= 0) {
            String list = notation.substring(values + "(values:".length(), notation.length() - 1);
            return new Rule(Kind.VALUES, Set.copyOf(Chars.pieces(list, ',')), notation);
        }
        return new Rule(Kind.ANY, Set.of(), notation);
    }
}
