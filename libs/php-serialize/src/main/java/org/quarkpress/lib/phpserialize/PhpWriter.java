package org.quarkpress.lib.phpserialize;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Writes a {@link PhpValue} tree in PHP {@code serialize()} format. */
final class PhpWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private PhpWriter() {}

    static byte[] write(PhpValue value) {
        PhpWriter w = new PhpWriter();
        w.writeValue(value);
        return w.out.toByteArray();
    }

    private void writeValue(PhpValue value) {
        switch (value) {
            case PhpNull n -> ascii("N;");
            case PhpBool b -> ascii(b.value() ? "b:1;" : "b:0;");
            case PhpInt i -> ascii("i:" + i.value() + ";");
            case PhpFloat f -> ascii("d:" + formatFloat(f) + ";");
            case PhpString s -> {
                ascii("s:");
                lengthPrefixed(s.bytes());
                ascii(";");
            }
            case PhpArray a -> {
                ascii("a:");
                entries(a);
            }
            case PhpObject o -> {
                ascii("O:");
                lengthPrefixed(o.className().getBytes(StandardCharsets.UTF_8));
                ascii(":");
                entries(o.properties());
            }
            case PhpCustomObject c -> {
                byte[] payload = c.payload();
                ascii("C:");
                lengthPrefixed(c.className().getBytes(StandardCharsets.UTF_8));
                ascii(":" + payload.length + ":{");
                out.writeBytes(payload);
                ascii("}");
            }
            case PhpReference r -> ascii((r.kind() == PhpReference.Kind.OBJECT ? "r:" : "R:") + r.index() + ";");
            case PhpEnum e -> {
                ascii("E:");
                lengthPrefixed((e.className() + ":" + e.caseName()).getBytes(StandardCharsets.UTF_8));
                ascii(";");
            }
        }
    }

    private void entries(PhpArray array) {
        ascii(array.size() + ":{");
        for (Map.Entry<PhpArray.Key, PhpValue> e : array.entries().entrySet()) {
            switch (e.getKey()) {
                case PhpArray.Key.Int k -> ascii("i:" + k.value() + ";");
                case PhpArray.Key.Str k -> {
                    ascii("s:");
                    lengthPrefixed(k.value().bytes());
                    ascii(";");
                }
            }
            writeValue(e.getValue());
        }
        ascii("}");
    }

    /** Writes {@code LEN:"BYTES"}. */
    private void lengthPrefixed(byte[] bytes) {
        ascii(bytes.length + ":\"");
        out.writeBytes(bytes);
        ascii("\"");
    }

    private void ascii(String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    /** Uses the parsed lexeme when present; otherwise the shortest Java repr, PHP-style. */
    static String formatFloat(PhpFloat f) {
        if (f.raw() != null) {
            return f.raw();
        }
        double d = f.value();
        if (Double.isNaN(d)) {
            return "NAN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "INF" : "-INF";
        }
        String s = Double.toString(d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
