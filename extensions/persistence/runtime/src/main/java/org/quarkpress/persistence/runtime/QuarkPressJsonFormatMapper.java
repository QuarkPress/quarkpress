package org.quarkpress.persistence.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.quarkus.hibernate.orm.JsonFormat;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;

/**
 * Reads and writes the JSON columns of QuarkPress's persistence unit with its own ObjectMapper, so neither a missing
 * nor a customized application ObjectMapper changes how options are stored. The application's own unit is untouched.
 *
 * <p>Object keys keep their order. Numbers are read exactly: decimals as {@code BigDecimal} (trailing zeros kept),
 * integers as int or long when they fit and as {@code BigInteger} otherwise, so a value reads back the same on every
 * database. Strings may contain U+0000; a value whose text contains an unpaired surrogate is refused, since it has no
 * UTF-8 form.
 */
@JsonFormat
@PersistenceUnitExtension("quarkpress")
@ApplicationScoped
public class QuarkPressJsonFormatMapper implements FormatMapper {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private final FormatMapper delegate = new JacksonJsonFormatMapper(MAPPER);

    /** The text this unit stores for {@code value}: the same mapper writes it, so key order and numbers match. */
    public static String write(JsonNode value) {
        try {
            return requireWellFormed(MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot write a JSON value: " + e.getOriginalMessage(), e);
        }
    }

    @Override
    public <T> T fromString(CharSequence text, JavaType<T> javaType, WrapperOptions options) {
        return delegate.fromString(text, javaType, options);
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType, WrapperOptions options) {
        return requireWellFormed(delegate.toString(value, javaType, options));
    }

    static String requireWellFormed(String json) {
        if (json == null) {
            return null;
        }
        int length = json.length();
        for (int i = 0; i < length; i++) {
            char c = json.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < length && Character.isLowSurrogate(json.charAt(i + 1))) {
                    i++;
                    continue;
                }
                throw new IllegalArgumentException("a JSON value contains an unpaired surrogate at index " + i);
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("a JSON value contains an unpaired surrogate at index " + i);
            }
        }
        return json;
    }
}
