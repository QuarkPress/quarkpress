package org.quarkpress.lib.wpshortcode;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The HTML pass that runs before the main shortcode pass (R12): shortcodes inside element attributes are
 * expanded one attribute at a time, and every bracket left inside an element is hidden as {@code &#91;} /
 * {@code &#93;} so the main pass cannot match across markup. The brackets come back with the final unescape.
 * One pass over the text; each element is parsed once.
 */
final class HtmlProtector {

    private HtmlProtector() {}

    static String protect(
            String content,
            boolean ignoreHtml,
            Function<String, ShortcodeScanner.Expansion> expander,
            HtmlAttributeSanitizer sanitizer) {
        int lt = content.indexOf('<');
        if (lt < 0) {
            return content;
        }
        StringBuilder out = new StringBuilder(content.length() + 32);
        int i = 0;
        while (lt >= 0) {
            out.append(content, i, lt);
            int end = HtmlElement.end(content, lt);
            out.append(element(content.substring(lt, end), ignoreHtml, expander, sanitizer));
            i = end;
            lt = content.indexOf('<', end);
        }
        out.append(content, i, content.length());
        return out.toString();
    }

    private static String element(
            String element,
            boolean ignoreHtml,
            Function<String, ShortcodeScanner.Expansion> expander,
            HtmlAttributeSanitizer sanitizer) {
        boolean open = element.indexOf('[') >= 0;
        boolean close = element.indexOf(']') >= 0;
        if (!open || !close) {
            return open || close ? EntityCodec.encodeBrackets(element) : element;
        }
        if (ignoreHtml || element.startsWith("<!--") || element.startsWith("<![CDATA[")) {
            return EntityCodec.encodeBrackets(element);
        }
        HtmlElement.Parsed parsed = HtmlElement.parse(element);
        if (parsed == null) {
            String text = HtmlElement.startsWithBracketedName(element)
                    ? expander.apply(element).text()
                    : element;
            return EntityCodec.encodeBrackets(text);
        }
        StringBuilder out = new StringBuilder(element.length() + 16).append(parsed.front());
        for (String attribute : parsed.attributes()) {
            out.append(attribute(attribute, parsed.name(), expander, sanitizer));
        }
        return EntityCodec.encodeBrackets(out.append(parsed.back()).toString());
    }

    private static String attribute(
            String attribute,
            String elementName,
            Function<String, ShortcodeScanner.Expansion> expander,
            HtmlAttributeSanitizer sanitizer) {
        int open = attribute.indexOf('[');
        if (open < 0 || attribute.indexOf(']') < 0) {
            return attribute;
        }
        int doubleQuote = attribute.indexOf('"');
        int singleQuote = attribute.indexOf('\'');
        boolean unquoted = (singleQuote < 0 || open < singleQuote) && (doubleQuote < 0 || open < doubleQuote);
        ShortcodeScanner.Expansion expansion = expander.apply(attribute);
        if (unquoted) {
            return expansion.text(); // trusted author: raw output, no sanitizing
        }
        if (expansion.replacements() == 0) {
            return attribute;
        }
        Optional<String> clean = Objects.requireNonNull(
                sanitizer.sanitize(elementName, expansion.text()), "an HtmlAttributeSanitizer returned null");
        return clean.filter(c -> !Chars.isPhpBlank(c)).orElse(attribute);
    }
}
