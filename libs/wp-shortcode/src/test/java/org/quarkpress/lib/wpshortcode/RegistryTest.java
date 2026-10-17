package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RegistryTest {

    private static final ShortcodeHandler ONE = (s, c) -> "one";
    private static final ShortcodeHandler TWO = (s, c) -> "two";
    // built without literals so the formatter cannot turn escapes into raw non-ASCII characters (ruling M1)
    private static final String U_UMLAUT = String.valueOf((char) 0xFC);
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    @Test
    void replacingANameKeepsItsPosition() { // R1.1
        ShortcodeRegistry registry = ShortcodeRegistry.builder()
                .add("dup", ONE)
                .add("z", ONE)
                .add("dup", TWO)
                .build();
        assertThat(registry.names()).containsExactly("dup", "z");
        assertThat(registry.handler("dup")).containsSame(TWO);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void rejectsNamesWordPressRejects() { // R1.2 (WordPress: doing_it_wrong and ignore; here: fail fast)
        for (String name :
                new String[] {"", "  ", "a b", "a/b", "a=b", "a&b", "a<b", "a>b", "a[b", "a]b", "a\tb", "a\u0001b"}) {
            assertThatThrownBy(() -> ShortcodeRegistry.builder().add(name, ONE))
                    .as("%s", name)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("shortcode name");
        }
    }

    @Test
    void acceptsNamesWordPressAccepts() { // R1.2
        ShortcodeRegistry.Builder builder = ShortcodeRegistry.builder();
        String[] names = {"a\"b", "a'b", "a|b", "a{b}", "-x", "a.b", "a\u007f", U_UMLAUT, "a:b", EMOJI};
        for (String name : names) {
            builder.add(name, ONE);
        }
        assertThat(builder.build().names()).containsExactly(names);
    }

    @Test
    void namesAreCaseSensitive() { // R1.3
        ShortcodeRegistry registry = ShortcodeRegistry.builder().add("a.b", ONE).build();
        assertThat(registry.contains("a.b")).isTrue();
        assertThat(registry.contains("A.B")).isFalse();
    }

    @Test
    void removeAndClear() { // R1.4
        ShortcodeRegistry.Builder builder =
                ShortcodeRegistry.builder().add("a", ONE).add("b", ONE);
        assertThat(builder.remove("missing").remove("a").build().names()).containsExactly("b");
        assertThat(builder.clear().build().isEmpty()).isTrue();
    }

    @Test
    void builtRegistriesDoNotChangeLater() {
        ShortcodeRegistry.Builder builder = ShortcodeRegistry.builder().add("a", ONE);
        ShortcodeRegistry first = builder.build();
        builder.add("b", ONE);
        assertThat(first.names()).containsExactly("a");
        assertThat(first.toBuilder().add("c", ONE).build().names()).containsExactly("a", "c");
        assertThat(first.names()).containsExactly("a");
    }

    @Test
    void rejectsNulls() {
        assertThatNullPointerException()
                .isThrownBy(() -> ShortcodeRegistry.builder().add(null, ONE));
        assertThatNullPointerException()
                .isThrownBy(() -> ShortcodeRegistry.builder().add("a", null));
        assertThatNullPointerException()
                .isThrownBy(() -> ShortcodeRegistry.builder().build().contains(null));
    }
}
