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
                entries(a, false);
            }
            case PhpObject o -> {
                ascii("O:");
                lengthPrefixed(o.className().getBytes(StandardCharsets.UTF_8));
                ascii(":");
                entries(o.properties(), true);
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

    /** Writes {@code COUNT:{key;value;...}}. PHP stores property names as strings, so an integer name is written as text. */
    private void entries(PhpArray array, boolean propertyNames) {
        ascii(array.size() + ":{");
        for (Map.Entry<PhpArray.Key, PhpValue> e : array.entries().entrySet()) {
            switch (e.getKey()) {
                case PhpArray.Key.Int k -> {
                    if (propertyNames) {
                        ascii("s:");
                        lengthPrefixed(Long.toString(k.value()).getBytes(StandardCharsets.US_ASCII));
                        ascii(";");
                    } else {
                        ascii("i:" + k.value() + ";");
                    }
                }
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

    /**
     * The text PHP's serialize() writes for the value. A parsed lexeme is not reused: PHP normalizes {@code +1.5},
     * {@code 1.50}, {@code 1e3} and {@code .5} the same way, and the lexemes PHP itself writes come back unchanged.
     */
    static String formatFloat(PhpFloat f) {
        return PhpFloatFormat.shortest(f.value());
    }
}
