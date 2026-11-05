package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.quarkpress.media.Crop;
import org.quarkpress.media.ImageSizeDefinition;
import org.quarkpress.media.ImageSizeProvider;

/**
 * WordPress's built-in image sizes and how a site's sizes are resolved. The four option sizes ({@code thumbnail},
 * {@code medium}, {@code medium_large}, {@code large}) take their box from the site options {@code {name}_size_w} and
 * {@code {name}_size_h} and their crop from {@code {name}_crop}; only {@code thumbnail_crop} has a default (on). The
 * two code sizes {@code 1536x1536} and {@code 2048x2048} are registered before any provider's.
 */
public final class BuiltInImageSizes {

    /** An option size with the values a new site has. */
    private record OptionSize(String name, int width, int height, boolean crop) {}

    private static final List<OptionSize> OPTION_SIZES = List.of(
            new OptionSize("thumbnail", 150, 150, true),
            new OptionSize("medium", 300, 300, false),
            new OptionSize("medium_large", 768, 0, false),
            new OptionSize("large", 1024, 1024, false));

    /** The sizes registered in code before any provider's. */
    public static final List<ImageSizeDefinition> CODE_SIZES = List.of(
            new ImageSizeDefinition("1536x1536", 1536, 1536, Crop.none()),
            new ImageSizeDefinition("2048x2048", 2048, 2048, Crop.none()));

    private BuiltInImageSizes() {}

    /**
     * The registrations: the code sizes, then each provider's sizes in the providers' order. A name registered again
     * keeps its first place and takes the later definition.
     */
    public static Map<String, ImageSizeDefinition> registrations(List<? extends ImageSizeProvider> providers) {
        Map<String, ImageSizeDefinition> registered = new LinkedHashMap<>();
        for (ImageSizeDefinition size : CODE_SIZES) {
            registered.put(size.name(), size);
        }
        for (ImageSizeProvider provider : providers) {
            for (ImageSizeDefinition size : provider.sizes()) {
                registered.put(size.name(), size);
            }
        }
        return Collections.unmodifiableMap(registered);
    }

    /**
     * A site's sizes: the option sizes in their fixed order, each from its registration when one exists, else from
     * {@code option} (a missing option takes the new-site value); then the other registrations in order. Sizes of 0x0
     * are left out.
     */
    public static Map<String, ImageSizeDefinition> resolve(
            Map<String, ImageSizeDefinition> registrations, Function<String, Optional<JsonNode>> option) {
        Map<String, ImageSizeDefinition> sizes = new LinkedHashMap<>();
        for (OptionSize size : OPTION_SIZES) {
            ImageSizeDefinition registered = registrations.get(size.name());
            sizes.put(size.name(), registered != null ? registered : fromOptions(size, option));
        }
        for (ImageSizeDefinition registered : registrations.values()) {
            sizes.putIfAbsent(registered.name(), registered);
        }
        sizes.values().removeIf(size -> size.width() == 0 && size.height() == 0);
        return Collections.unmodifiableMap(sizes);
    }

    private static ImageSizeDefinition fromOptions(OptionSize size, Function<String, Optional<JsonNode>> option) {
        int width = option.apply(size.name() + "_size_w")
                .map(BuiltInImageSizes::phpInt)
                .orElse(size.width());
        int height = option.apply(size.name() + "_size_h")
                .map(BuiltInImageSizes::phpInt)
                .orElse(size.height());
        Optional<JsonNode> crop = option.apply(size.name() + "_crop");
        return new ImageSizeDefinition(
                size.name(),
                width,
                height,
                crop.isPresent() ? Crop.fromOption(crop.get()) : size.crop() ? Crop.hard() : Crop.none());
    }

    /**
     * An option value as PHP's integer cast reads it: numbers truncated, {@code true} 1, a text by its leading number
     * ({@code "12px"} 12, {@code "abc"} 0), a non-empty array 1, anything else 0; kept within {@code int}.
     */
    static int phpInt(JsonNode value) {
        double number;
        if (value.isIntegralNumber()) {
            return clamp(value.canConvertToLong() ? (double) value.longValue() : value.doubleValue());
        } else if (value.isNumber()) {
            number = value.doubleValue();
        } else if (value.isBoolean()) {
            number = value.booleanValue() ? 1 : 0;
        } else if (value.isTextual()) {
            number = leadingNumber(value.textValue());
        } else if (value.isContainerNode()) {
            number = value.size() > 0 ? 1 : 0;
        } else {
            number = 0;
        }
        return clamp(number);
    }

    private static int clamp(double number) {
        if (Double.isNaN(number)) {
            return 0;
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number));
    }

    /** The number at the start of {@code text} after leading white space (digits, a fraction, an exponent), or 0. */
    private static double leadingNumber(String text) {
        int i = 0;
        int n = text.length();
        while (i < n && " \t\n\r\u000B\f".indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        int start = i;
        if (i < n && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        int digits = i;
        while (i < n && isDigit(text.charAt(i))) {
            i++;
        }
        boolean any = i > digits;
        if (i < n && text.charAt(i) == '.') {
            int fraction = ++i;
            while (i < n && isDigit(text.charAt(i))) {
                i++;
            }
            any |= i > fraction;
            if (!any) {
                return 0;
            }
        }
        if (!any) {
            return 0;
        }
        int end = i;
        if (i < n && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (text.charAt(j) == '+' || text.charAt(j) == '-')) {
                j++;
            }
            int exponent = j;
            while (j < n && isDigit(text.charAt(j))) {
                j++;
            }
            if (j > exponent) {
                end = j;
            }
        }
        return Double.parseDouble(text.substring(start, end));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
