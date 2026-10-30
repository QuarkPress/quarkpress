package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * JSON as WordPress's REST server writes it: PHP's {@code json_encode} with the options WordPress passes (none, or
 * pretty printing).
 */
public final class RestJson {

    /**
     * The nodes the module builds and reads: every integer is a {@code LongNode}, as PHP has a single integer type,
     * and an integer outside the 64-bit range becomes a {@code DoubleNode}, as PHP's {@code json_decode} makes it a
     * float. Stateless and shared.
     */
    public static final JsonNodeFactory NODES = new PhpNodes();

    /** The module's mapper: never the application's, and never handed out (it is mutable). */
    private static final ObjectMapper MAPPER = mapper();

    /**
     * Reads JSON trees as PHP's {@code json_decode} reads them: object key order kept, integers within the 64-bit range
     * as exact longs, integers outside it and every number with a fraction or exponent as doubles. Immutable.
     */
    public static final ObjectReader READER = MAPPER.reader();

    private RestJson() {}

    /**
     * PHP's json_encode as WordPress calls it: '/' as "\/", non-ASCII as lower-case \\uXXXX (surrogate pairs above
     * U+FFFF, each half on its own), control characters other than \b \f \n \r \t as lower-case \\u00xx, '<' '>' '&'
     * '\'' raw, integral doubles without ".0"; pretty = PHP's pretty print (4 spaces, "key": value, empty containers
     * as [] and {}).
     *
     * <p>Doubles are written as PHP writes them with its default {@code serialize_precision} of -1: the shortest
     * digits that read back as the same double, in plain notation when the decimal exponent is from -4 to 16 (an
     * integral value without fraction), otherwise as {@code d.ddde+x} (at least one fraction digit). Big integers and
     * big decimals are written as the doubles PHP would hold.
     *
     * @throws IllegalArgumentException when {@code body} holds NaN or an infinity, which json_encode refuses ("Inf and
     *     NaN cannot be JSON encoded")
     */
    public static byte[] encode(JsonNode body, boolean pretty) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JsonGenerator generator = new PhpNumbers(MAPPER.getFactory().createGenerator(bytes, JsonEncoding.UTF8))) {
            if (pretty) {
                generator.setPrettyPrinter(new PhpPrettyPrinter());
            }
            MAPPER.writeTree(generator, body);
        } catch (IOException e) {
            if (e.getCause() instanceof IllegalArgumentException refused) {
                throw refused;
            }
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static ObjectMapper mapper() {
        // USE_LONG_FOR_INTS stays off: under it Jackson refuses integers beyond the long range instead of handing them
        // to the node factory, which turns them into doubles.
        ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(DeserializationFeature.USE_LONG_FOR_INTS)
                .setNodeFactory(NODES);
        mapper.getFactory().setCharacterEscapes(new PhpEscapes());
        return mapper;
    }

    /** The decimal text PHP's json_encode writes for a finite double. */
    static String phpDouble(double value) {
        if (value == 0) {
            return Double.doubleToRawLongBits(value) < 0 ? "-0" : "0";
        }
        // Double.toString gives the shortest digits that read back as the same value, as PHP's precision -1 does,
        // except that it never gives fewer than two (4.9E-324 where PHP writes 5.0e-324).
        double magnitude = Math.abs(value);
        BigDecimal decimal = new BigDecimal(Double.toString(magnitude)).stripTrailingZeros();
        if (decimal.precision() == 2) {
            BigDecimal oneDigit = decimal.round(new MathContext(1, RoundingMode.HALF_EVEN));
            if (oneDigit.doubleValue() == magnitude) {
                decimal = oneDigit.stripTrailingZeros();
            }
        }
        String digits = decimal.unscaledValue().toString();
        int count = digits.length();
        int exponent = count - 1 - decimal.scale();
        StringBuilder text = new StringBuilder(count + 8);
        if (value < 0) {
            text.append('-');
        }
        if (exponent < -4 || exponent >= 17) {
            text.append(digits.charAt(0)).append('.');
            if (count > 1) {
                text.append(digits, 1, count);
            } else {
                text.append('0');
            }
            text.append('e').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        } else if (exponent >= count - 1) {
            text.append(digits);
            for (int i = count - 1; i < exponent; i++) {
                text.append('0');
            }
        } else if (exponent >= 0) {
            text.append(digits, 0, exponent + 1).append('.').append(digits, exponent + 1, count);
        } else {
            text.append("0.");
            for (int i = -1; i > exponent; i--) {
                text.append('0');
            }
            text.append(digits);
        }
        return text.toString();
    }

    /**
     * PHP's string escapes: Jackson's two-character escapes for {@code " \ \b \f \n \r \t}; {@code /} as {@code \/};
     * every other control character and every char above 0x7F (each surrogate on its own) as {@code \}{@code u} with
     * four lower-case hex digits. DEL (0x7F) stays raw, as in PHP.
     */
    private static final class PhpEscapes extends CharacterEscapes {

        private static final char[] HEX = "0123456789abcdef".toCharArray();

        private static final long serialVersionUID = 1L;

        private static final SerializedString SLASH = new SerializedString("\\/");

        private final int[] ascii;

        /** The {@code \}{@code uXXXX} escape of each char, made the first time the char is escaped. */
        private final SerializedString[] escapes = new SerializedString[0x10000];

        PhpEscapes() {
            int[] codes = CharacterEscapes.standardAsciiEscapesForJSON();
            for (int c = 0; c < 0x20; c++) {
                if (c != '\b' && c != '\f' && c != '\n' && c != '\r' && c != '\t') {
                    codes[c] = CharacterEscapes.ESCAPE_CUSTOM;
                }
            }
            codes['/'] = CharacterEscapes.ESCAPE_CUSTOM;
            this.ascii = codes;
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return ascii;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            if (ch == '/') {
                return SLASH;
            }
            SerializedString cached = escapes[ch];
            if (cached == null) {
                // racing threads may each make one; any of them is the same text
                cached = unicodeEscape(ch);
                escapes[ch] = cached;
            }
            return cached;
        }

        private static SerializedString unicodeEscape(int ch) {
            char[] escape = new char[6];
            escape[0] = '\\';
            escape[1] = 'u';
            for (int i = 5; i >= 2; i--) {
                escape[i] = HEX[ch & 0xF];
                ch >>>= 4;
            }
            return new SerializedString(new String(escape));
        }
    }

    /** {@link #NODES}: integers as longs, integers beyond the long range as doubles. */
    private static final class PhpNodes extends JsonNodeFactory {

        private static final long serialVersionUID = 1L;

        @Override
        public NumericNode numberNode(byte value) {
            return LongNode.valueOf(value);
        }

        @Override
        public NumericNode numberNode(short value) {
            return LongNode.valueOf(value);
        }

        @Override
        public NumericNode numberNode(int value) {
            return LongNode.valueOf(value);
        }

        @Override
        public ValueNode numberNode(BigInteger value) {
            if (value == null) {
                return nullNode();
            }
            if (value.bitLength() < 64) {
                return LongNode.valueOf(value.longValue());
            }
            return DoubleNode.valueOf(value.doubleValue());
        }
    }

    /** Writes every floating-point number as PHP does (see {@link #phpDouble}). */
    private static final class PhpNumbers extends JsonGeneratorDelegate {

        PhpNumbers(JsonGenerator delegate) {
            super(delegate, false);
        }

        @Override
        public void writeNumber(double value) throws IOException {
            if (Double.isFinite(value)) {
                delegate.writeNumber(phpDouble(value));
            } else {
                // json_encode refuses infinities and NaN; encode hands this exception to its caller unwrapped
                throw new IllegalArgumentException("Inf and NaN cannot be JSON encoded: " + value);
            }
        }

        @Override
        public void writeNumber(float value) throws IOException {
            writeNumber(Double.parseDouble(Float.toString(value)));
        }

        @Override
        public void writeNumber(BigDecimal value) throws IOException {
            writeNumber(value.doubleValue());
        }

        @Override
        public void writeNumber(BigInteger value) throws IOException {
            if (value.bitLength() < 64) {
                delegate.writeNumber(value.longValue());
            } else {
                writeNumber(value.doubleValue());
            }
        }
    }

    /** PHP's {@code JSON_PRETTY_PRINT}: four spaces per level, {@code "key": value}, empty containers as [] and {}. */
    private static final class PhpPrettyPrinter implements PrettyPrinter {

        private int level;

        @Override
        public void writeRootValueSeparator(JsonGenerator generator) throws IOException {
            // one value per document
        }

        @Override
        public void writeStartObject(JsonGenerator generator) throws IOException {
            generator.writeRaw('{');
            level++;
        }

        @Override
        public void beforeObjectEntries(JsonGenerator generator) throws IOException {
            newLine(generator);
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator generator) throws IOException {
            generator.writeRaw(": ");
        }

        @Override
        public void writeObjectEntrySeparator(JsonGenerator generator) throws IOException {
            generator.writeRaw(',');
            newLine(generator);
        }

        @Override
        public void writeEndObject(JsonGenerator generator, int entries) throws IOException {
            level--;
            if (entries > 0) {
                newLine(generator);
            }
            generator.writeRaw('}');
        }

        @Override
        public void writeStartArray(JsonGenerator generator) throws IOException {
            generator.writeRaw('[');
            level++;
        }

        @Override
        public void beforeArrayValues(JsonGenerator generator) throws IOException {
            newLine(generator);
        }

        @Override
        public void writeArrayValueSeparator(JsonGenerator generator) throws IOException {
            generator.writeRaw(',');
            newLine(generator);
        }

        @Override
        public void writeEndArray(JsonGenerator generator, int values) throws IOException {
            level--;
            if (values > 0) {
                newLine(generator);
            }
            generator.writeRaw(']');
        }

        private void newLine(JsonGenerator generator) throws IOException {
            generator.writeRaw('\n');
            for (int i = 0; i < level; i++) {
                generator.writeRaw("    ");
            }
        }
    }
}
