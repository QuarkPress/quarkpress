package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.lib.wpshortcode.ShortcodeAttributes.Entry;
import org.quarkpress.lib.wpshortcode.ShortcodeAttributes.Key;

class ShortcodeAttributesTest {

    private static Entry named(String name, String value) {
        return new Entry(new Key.Name(name), value);
    }

    private static Entry indexed(long index, String value) {
        return new Entry(new Key.Index(index), value);
    }

    @Test
    void digitNamesBecomeIntegerKeysLikePhpArrayKeys() {
        assertThat(ShortcodeAttributes.keyOf("5")).isEqualTo(new Key.Index(5));
        assertThat(ShortcodeAttributes.keyOf("-1")).isEqualTo(new Key.Index(-1));
        assertThat(ShortcodeAttributes.keyOf("0")).isEqualTo(new Key.Index(0));
        assertThat(ShortcodeAttributes.keyOf("-9223372036854775808")).isEqualTo(new Key.Index(Long.MIN_VALUE));
        assertThat(ShortcodeAttributes.keyOf("9223372036854775807")).isEqualTo(new Key.Index(Long.MAX_VALUE));
        for (String name : new String[] {"00", "007", "-0", "-", "", "9223372036854775808", "1a", "+1", " 1"}) {
            assertThat(ShortcodeAttributes.keyOf(name)).as(name).isEqualTo(new Key.Name(name));
        }
    }

    @Test
    void repeatedKeysKeepTheirFirstPosition() {
        ShortcodeAttributes.Builder b = new ShortcodeAttributes.Builder();
        b.put("a", "1");
        b.put("b", "2");
        b.put("a", "3");
        assertThat(b.build().entries()).containsExactly(named("a", "3"), named("b", "2"));
    }

    @Test
    void positionalsTakeTheNextIntegerKey() {
        // "5=a 3=b c" -> 5, 3, 6 ; "-5=a b" -> -5, -4 ; "x 1=a y" -> 0, 1, 2
        ShortcodeAttributes.Builder b = new ShortcodeAttributes.Builder();
        b.put("5", "a");
        b.put("3", "b");
        b.append("c");
        assertThat(b.build().entries()).containsExactly(indexed(5, "a"), indexed(3, "b"), indexed(6, "c"));

        b = new ShortcodeAttributes.Builder();
        b.put("-5", "a");
        b.append("b");
        assertThat(b.build().entries()).containsExactly(indexed(-5, "a"), indexed(-4, "b"));

        b = new ShortcodeAttributes.Builder();
        b.append("x");
        b.put("1", "a");
        b.append("y");
        assertThat(b.build().entries()).containsExactly(indexed(0, "x"), indexed(1, "a"), indexed(2, "y"));
    }

    @Test
    void positionalAfterTheLargestKeyIsDropped() {
        ShortcodeAttributes.Builder b = new ShortcodeAttributes.Builder();
        b.put("9223372036854775806", "a");
        b.append("b");
        b.append("c"); // PHP 8.4 throws here; this library drops it
        assertThat(b.build().entries()).containsExactly(indexed(Long.MAX_VALUE - 1, "a"), indexed(Long.MAX_VALUE, "b"));
    }

    @Test
    void blanksValuesWithAnUnclosedTag() {
        ShortcodeAttributes.Builder b = new ShortcodeAttributes.Builder();
        b.put("a", "<b>bold</b>");
        b.put("b", "<b");
        b.put("c", "x>y");
        b.put("d", "<a><");
        b.put("e", "<a<b>");
        b.append("x>y<z");
        b.blankUnclosedTags();
        assertThat(b.build().entries())
                .containsExactly(
                        named("a", "<b>bold</b>"),
                        named("b", ""),
                        named("c", "x>y"),
                        named("d", ""),
                        named("e", "<a<b>"),
                        indexed(0, ""));
    }

    @Test
    void viewsSplitNamesFromIntegerKeys() {
        ShortcodeAttributes.Builder b = new ShortcodeAttributes.Builder();
        b.put("a", "1");
        b.append("p");
        b.put("5", "five");
        b.put("data-x", "d");
        ShortcodeAttributes attributes = b.build();

        assertThat(attributes.named()).containsExactly(Map.entry("a", "1"), Map.entry("data-x", "d"));
        assertThat(attributes.positional()).containsExactly("p", "five");
        assertThat(attributes.get("a")).contains("1");
        assertThat(attributes.get("A")).isEmpty();
        assertThat(attributes.get("5")).contains("five");
        assertThat(attributes.get(5)).contains("five");
        assertThat(attributes.get("0")).contains("p");
        assertThat(attributes.get(1)).isEmpty();
        assertThat(attributes.size()).isEqualTo(4);
        assertThat(attributes.isEmpty()).isFalse();
        assertThat(ShortcodeAttributes.empty().isEmpty()).isTrue();
    }

    @Test
    void withDefaultsKeepsExactlyTheDefaultKeys() {
        // shortcode_atts({a:1,b:2,C:3}, {a:x,c:y,z:q,0:pos}) -> {a:x,b:2,C:3}
        ShortcodeAttributes.Builder b = new ShortcodeAttributes.Builder();
        b.put("a", "x");
        b.put("c", "y");
        b.put("z", "q");
        b.append("pos");
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("a", "1");
        defaults.put("b", "2");
        defaults.put("C", "3");
        defaults.put("0", "zero");
        defaults.put("id", null);

        Map<String, String> out = b.build().withDefaults(defaults);

        assertThat(out.keySet()).containsExactly("a", "b", "C", "0", "id");
        assertThat(out.get("a")).isEqualTo("x");
        assertThat(out.get("b")).isEqualTo("2");
        assertThat(out.get("C")).isEqualTo("3");
        assertThat(out.get("0")).isEqualTo("pos");
        assertThat(out.get("id")).isNull();
    }

    @Test
    void tagFormRunsTheBoundFilterUnlessTheTagIsEmptyOrZero() {
        ShortcodeAttributes.Builder b = new ShortcodeAttributes.Builder();
        b.put("a", "x");
        ShortcodeAttributes.DefaultsFilter filter = (tag, out, defaults, attributes) -> {
            Map<String, String> copy = new LinkedHashMap<>(out);
            copy.put("extra", tag);
            return copy;
        };
        ShortcodeAttributes bound = b.build().bind(filter);
        Map<String, String> defaults = Map.of("a", "1");

        assertThat(bound.withDefaults(defaults, "gal")).containsExactly(Map.entry("a", "x"), Map.entry("extra", "gal"));
        assertThat(bound.withDefaults(defaults, "")).containsExactly(Map.entry("a", "x"));
        assertThat(bound.withDefaults(defaults, "0")).containsExactly(Map.entry("a", "x"));
        assertThat(b.build().withDefaults(defaults, "gal")).containsExactly(Map.entry("a", "x"));
        assertThat(bound).isEqualTo(b.build());
    }

    @Test
    void equalityFollowsEntriesInOrder() {
        ShortcodeAttributes.Builder ab = new ShortcodeAttributes.Builder();
        ab.put("a", "1");
        ab.put("b", "2");
        ShortcodeAttributes.Builder ba = new ShortcodeAttributes.Builder();
        ba.put("b", "2");
        ba.put("a", "1");
        assertThat(ab.build()).isNotEqualTo(ba.build());
        assertThat(ab.build()).isEqualTo(ab.build()).hasSameHashCodeAs(ab.build());
        assertThat(ab.build().toString()).contains("a").contains("1");
    }
}
