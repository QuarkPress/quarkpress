package org.quarkpress.lib.wpkses;

import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;

/** Generators of hostile markup, glued from fragments so that near-misses of every rule occur often. */
final class KsesArbitraries {

    private KsesArbitraries() {}

    /** HTML soup: tags, broken tags, comments, entities, quotes, URL schemes, style and block fragments. */
    static Gen<String> soup() {
        Gen<String> fragment = Gens.elements(
                "<",
                ">",
                "<b>",
                "</b>",
                "<a href=\"",
                "javascript:",
                "\"",
                "'",
                " ",
                "x",
                "&",
                "&amp;",
                "&#58;",
                "&colon;",
                "<!--",
                "-->",
                "<!",
                "<p style=\"",
                "color:red;",
                "font-family:'A'",
                "title=",
                "<img src=",
                "/>",
                "\\\"",
                "\n",
                "<script>",
                "=",
                "<!doctype",
                "</",
                "3",
                "onclick=x",
                "&lt;",
                "data-x=\"1\"",
                "<math>",
                "<mi ",
                "href=",
                "cite=",
                "feed:",
                "/?",
                "#",
                ":",
                "&#x3a;",
                "\t",
                "<a ",
                " src=\"",
                "vbscript:",
                "<object data=\"",
                "<!-- wp:p {\"a\":\"",
                "\"} /-->",
                "\\u003c",
                "url(",
                ")",
                "\r\n",
                "&nbsp;",
                "&#0;",
                "<iframe ",
                "<svg onload=",
                "<!-- wp:template-part {\"tagName\":\"",
                "---->",
                "\u00e9",
                "\u00a0");
        return Gens.lists(fragment, 0, 30).map(parts -> String.join("", parts));
    }

    /** Start tags of allowed and forbidden elements with many attribute shapes. */
    static Gen<String> tags() {
        Gen<String> element = Gens.elements("a", "A", "img", "p", "span", "object", "mi", "div", "script", "x-y", "td");
        Gen<String> name = Gens.elements(
                "href",
                "HREF",
                "src",
                "title",
                "style",
                "class",
                "data-a",
                "data-A_b",
                "data-",
                "onclick",
                "cite",
                "download",
                "type",
                "data",
                "xml:lang",
                "aria-label",
                "\"q\"",
                "=",
                "longdesc",
                "usemap");
        Gen<String> value = Gens.elements(
                "",
                "x",
                "javascript:alert(1)",
                " java&#115;cript:x",
                "http://ok",
                "https://x/a.pdf",
                "a&b",
                "&lt;b",
                "color:red;behavior:url(x)",
                "font-family:'A'",
                "#a:b",
                "/?a:b",
                "feed:feed:x",
                "&#x6A;avascript:x",
                "application/pdf",
                "y");
        Gen<String> quote = Gens.elements("\"", "'", "");
        Gen<String> attribute = random -> {
            String q = quote.next(random);
            return " " + name.next(random) + (random.nextInt(4) == 0 ? "" : "=" + q + value.next(random) + q);
        };
        return random -> {
            StringBuilder tag = new StringBuilder("<").append(element.next(random));
            int count = random.nextInt(5);
            for (int i = 0; i < count; i++) {
                tag.append(attribute.next(random));
            }
            return tag.append(random.nextBoolean() ? ">" : " />").append("t").toString();
        };
    }

    /** CSS declarations around the url(), gradient and function rules. */
    static Gen<String> css() {
        Gen<String> fragment = Gens.elements(
                "color:",
                "red",
                ";",
                " ",
                "background:",
                "url(",
                ")",
                "\"",
                "'",
                "javascript:",
                "a.png",
                "linear-gradient(",
                "calc(",
                "(",
                "var(--x)",
                "--x:",
                "/*",
                "*/",
                "&",
                "=",
                "}",
                "\\",
                "rgb(1,2,3)",
                "width:",
                "10px",
                "!important",
                "repeating-",
                "radial-gradient(",
                "0",
                "data:",
                "#x",
                "/?",
                ":");
        return Gens.lists(fragment, 0, 20).map(parts -> String.join("", parts));
    }

    /** URL-ish values for the protocol filter. */
    static Gen<String> urls() {
        Gen<String> fragment = Gens.elements(
                "javascript",
                "http",
                "https",
                ":",
                "&#58;",
                "&#058",
                "&#x3a;",
                "&#X3A",
                "&colon;",
                "&COLON;",
                "//",
                "x",
                "/?",
                "?",
                "#",
                " ",
                "\t",
                "\n",
                "\\0",
                "feed",
                "mailto",
                "&#106;",
                "0",
                "a@b",
                "%3a");
        return Gens.lists(fragment, 0, 14).map(parts -> String.join("", parts));
    }
}
