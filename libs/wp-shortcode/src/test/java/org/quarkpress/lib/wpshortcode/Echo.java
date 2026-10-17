package org.quarkpress.lib.wpshortcode;

import java.util.List;
import org.quarkpress.lib.wpshortcode.ShortcodeAttributes.Entry;
import org.quarkpress.lib.wpshortcode.ShortcodeAttributes.Key;

/**
 * The behavior reference's echo handler: {@code {TAG:ATTRS:CONTENT}}, with ATTRS and CONTENT written as PHP's
 * json_encode writes them with unescaped slashes and Unicode ({@code []} for no attributes, a list when the keys
 * are 0..n-1 in order, an object otherwise).
 */
final class Echo {

    static final ShortcodeHandler HANDLER =
            (s, context) -> "{" + s.tag() + ":" + json(s.attributes()) + ":" + json(s.content()) + "}";

    private Echo() {}

    static String json(ShortcodeAttributes attributes) {
        List<Entry> entries = attributes.entries();
        boolean list = true;
        for (int i = 0; i < entries.size() && list; i++) {
            list = entries.get(i).key() instanceof Key.Index index && index.index() == i;
        }
        StringBuilder out = new StringBuilder(list ? "[" : "{");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            Entry e = entries.get(i);
            if (!list) {
                String key =
                        e.key() instanceof Key.Name name ? name.name() : Long.toString(((Key.Index) e.key()).index());
                out.append(json(key)).append(':');
            }
            out.append(json(e.value()));
        }
        return out.append(list ? ']' : '}').toString();
    }

    static String json(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\u2028' -> out.append("\\u2028");
                case '\u2029' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
