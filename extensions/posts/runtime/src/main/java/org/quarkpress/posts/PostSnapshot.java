package org.quarkpress.posts;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * A post as stored. {@code publishedAt} is the GMT date (empty while a draft's date floats); the local date is
 * derived from it, or from {@code modifiedAt} while it floats, with the site's zone. {@code meta} maps each key to
 * its values in order; the values handed out are copies.
 */
public record PostSnapshot(
        PostId id,
        SiteId site,
        String type,
        String status,
        String slug,
        String title,
        String content,
        String excerpt,
        Optional<UserId> author,
        Optional<PostId> parent,
        int menuOrder,
        Optional<Instant> publishedAt,
        Instant createdAt,
        Instant modifiedAt,
        String commentStatus,
        String pingStatus,
        String password,
        boolean sticky,
        String guid,
        Map<String, List<JsonNode>> meta,
        int version) {

    public PostSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(excerpt, "excerpt");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        Objects.requireNonNull(commentStatus, "commentStatus");
        Objects.requireNonNull(pingStatus, "pingStatus");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(guid, "guid");
        meta = copy(Objects.requireNonNull(meta, "meta"));
    }

    @Override
    public Map<String, List<JsonNode>> meta() {
        return copy(meta);
    }

    /** The values of one meta key, in order; empty when the key is absent. */
    public List<JsonNode> meta(String key) {
        List<JsonNode> values = meta.get(key);
        return values == null
                ? List.of()
                : values.stream().map(value -> value.<JsonNode>deepCopy()).toList();
    }

    /**
     * The first value of a meta key as text, the way PHP converts a scalar to a string: {@code true} is "1",
     * {@code false} is "", a number is its decimal text (a double is written with 14 significant digits, so 1.0 is
     * "1", 0.0 is "0" and 1e14 is "1.0E+14"); empty when the key is absent, has no values, or the value is null or
     * a container.
     */
    public Optional<String> metaText(String key) {
        List<JsonNode> values = meta.get(key);
        if (values == null
                || values.isEmpty()
                || values.get(0).isContainerNode()
                || values.get(0).isNull()) {
            return Optional.empty();
        }
        JsonNode value = values.get(0);
        if (value.isBoolean()) {
            return Optional.of(value.booleanValue() ? "1" : "");
        }
        if (value.isFloatingPointNumber()) {
            return Optional.of(phpDouble(value.doubleValue()));
        }
        return Optional.of(value.asText());
    }

    /** WordPress's {@code post_date}: the GMT date in the zone, or the modification time while the date floats. */
    public LocalDateTime localDate(ZoneId zone) {
        return LocalDateTime.ofInstant(publishedAt.orElse(modifiedAt), zone);
    }

    /** WordPress's {@code post_modified}. */
    public LocalDateTime localModified(ZoneId zone) {
        return LocalDateTime.ofInstant(modifiedAt, zone);
    }

    /** Whether the password is not empty; a password of "0" counts here (see {@link PostPasswords#required}). */
    public boolean passwordProtected() {
        return !password.isEmpty();
    }

    /** PHP's string conversion of a float: 14 significant digits, scientific below 1e-4 and from 1e14 up. */
    private static String phpDouble(double number) {
        if (Double.isNaN(number)) {
            return "NAN";
        }
        if (Double.isInfinite(number)) {
            return number > 0 ? "INF" : "-INF";
        }
        if (number == 0) {
            return 1 / number < 0 ? "-0" : "0";
        }
        BigDecimal rounded = new BigDecimal(number).round(new MathContext(14, RoundingMode.HALF_EVEN));
        double magnitude = Math.abs(number);
        // PHP keeps the zeros when a whole number below 1e15 ending in 5 is a tie that rounded down.
        boolean keepZeros = magnitude >= 1e14
                && magnitude < 1e15
                && magnitude == Math.rint(magnitude)
                && ((long) magnitude) % 10 == 5
                && rounded.abs().compareTo(new BigDecimal(number).abs()) < 0;
        if (!keepZeros) {
            rounded = rounded.stripTrailingZeros();
        }
        int exponent = rounded.precision() - rounded.scale() - 1;
        if (exponent >= -4 && exponent < 14) {
            return rounded.toPlainString();
        }
        String digits = rounded.unscaledValue().abs().toString();
        StringBuilder text = new StringBuilder();
        if (rounded.signum() < 0) {
            text.append('-');
        }
        text.append(digits.charAt(0)).append('.');
        text.append(digits.length() == 1 ? "0" : digits.substring(1));
        text.append('E').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        return text.toString();
    }

    private static Map<String, List<JsonNode>> copy(Map<String, List<JsonNode>> meta) {
        Map<String, List<JsonNode>> copy = new LinkedHashMap<>();
        meta.forEach((key, values) -> copy.put(
                Objects.requireNonNull(key, "meta key"),
                Objects.requireNonNull(values, "meta values").stream()
                        .map(value ->
                                Objects.requireNonNull(value, "meta value").<JsonNode>deepCopy())
                        .toList()));
        return Collections.unmodifiableMap(copy);
    }
}
