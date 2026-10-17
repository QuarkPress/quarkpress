package org.quarkpress.core.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteOptions;

final class CachedSiteOptions implements SiteOptions {

    private static final int MAX_INTEGER_TEXT = 20;

    private final SiteId site;
    private final RequestOptionCache cache;

    CachedSiteOptions(SiteId site, RequestOptionCache cache) {
        this.site = site;
        this.cache = cache;
    }

    @Override
    public Optional<JsonNode> get(String name) {
        return cache.get(site, OptionNames.normalize(name));
    }

    @Override
    public String string(String name, String fallback) {
        return get(name).map(PhpValues::stringOf).orElse(fallback);
    }

    @Override
    public long number(String name, long fallback) {
        Optional<JsonNode> found = get(name);
        if (found.isEmpty()) {
            return fallback;
        }
        JsonNode value = found.get();
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            return parseLong(value.textValue()).orElse(fallback);
        }
        return fallback;
    }

    @Override
    public boolean flag(String name, boolean fallback) {
        Optional<JsonNode> found = get(name);
        if (found.isEmpty()) {
            return fallback;
        }
        JsonNode value = found.get();
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.doubleValue() != 0.0;
        }
        if (value.isTextual()) {
            String text = value.textValue();
            return !(text.isEmpty() || text.equals("0"));
        }
        if (value.isContainerNode()) {
            return value.size() > 0;
        }
        return false;
    }

    /** An optional minus sign and 1 to 19 ASCII digits, within the range of long. */
    static Optional<Long> parseLong(String text) {
        if (text.isEmpty() || text.length() > MAX_INTEGER_TEXT) {
            return Optional.empty();
        }
        boolean negative = text.charAt(0) == '-';
        int start = negative ? 1 : 0;
        if (start == text.length() || text.length() - start > 19) {
            return Optional.empty();
        }
        long value = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return Optional.empty();
            }
            int digit = c - '0';
            if (value > (Long.MAX_VALUE - digit) / 10) {
                return Optional.empty();
            }
            value = value * 10 + digit;
        }
        return Optional.of(negative ? -value : value);
    }
}
