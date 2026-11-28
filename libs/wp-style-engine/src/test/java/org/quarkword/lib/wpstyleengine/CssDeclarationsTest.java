package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CssDeclarationsTest {

    private static final List<String> PROPERTIES = List.of("color", "Color", "width", "--x", "0", "a b", "1", "");
    private static final List<String> VALUES = List.of("red", " ", "", "1px", "0", " blue ", "red;x:y", "\u000c");

    /** Random op lists give equal values through the builder and through chained {@code with}/{@code without}. */
    @Test
    void builderAndWithAgree() {
        Random random = new Random(20261127L);
        for (int run = 0; run < 500; run++) {
            CssDeclarations.Builder builder = CssDeclarations.builder();
            CssDeclarations chained = CssDeclarations.empty();
            int ops = random.nextInt(12);
            for (int i = 0; i < ops; i++) {
                String property = PROPERTIES.get(random.nextInt(PROPERTIES.size()));
                String value = VALUES.get(random.nextInt(VALUES.size()));
                switch (random.nextInt(4)) {
                    case 0 -> {
                        builder.add(property, value);
                        chained = chained.with(property, value);
                    }
                    case 1 -> {
                        builder.add(property, value, true);
                        chained = chained.with(property, value, true);
                    }
                    case 2 -> {
                        builder.remove(property);
                        chained = chained.without(property);
                    }
                    default -> {
                        CssDeclarations other = CssDeclarations.of(Map.of(property, value));
                        builder.addAll(other);
                        chained = chained.withAll(other);
                    }
                }
            }
            CssDeclarations built = builder.build();
            assertThat(built).isEqualTo(chained).hasSameHashCodeAs(chained);
            assertThat(built.toCss(true, 1)).isEqualTo(chained.toCss(true, 1));
            assertThat(built.toBuilder().build()).isEqualTo(built);
        }
    }

    @Test
    void blankValueKeepsExisting() {
        CssDeclarations d = CssDeclarations.of(Map.of("color", "red")).with("color", " ");
        assertThat(d.declarations()).containsExactly(Map.entry("color", "red"));
        assertThat(d.toCss()).isEqualTo("color:red;");
    }

    /** G135: !important only when the filtered declaration holds no ';'; a later add without the flag clears it. */
    @Test
    void importantOnlyWhenNoSemicolon() {
        CssDeclarations d = CssDeclarations.builder()
                .add("color", "red;width:1px", true)
                .add("height", "1px")
                .add("top", "0", false)
                .build();
        assertThat(d.important()).containsExactly("color");
        assertThat(d.toCss()).isEqualTo("color:red;width:1px;height:1px;top:0;");
        CssDeclarations flagged = d.with("height", "1px", true);
        assertThat(flagged.important()).containsExactly("color", "height");
        assertThat(flagged.toCss(true, 0)).isEqualTo("color: red;width:1px; height: 1px !important; top: 0;");
        assertThat(flagged.with("height", "2px").important()).containsExactly("color");
    }

    /** Found by the fuzz (t2-decl-zero-*): a filtered declaration of "0" is left out, unless !important is added. */
    @Test
    void zeroDeclarationIsFalsy() {
        assertThat(CssDeclarations.empty().with("--", "x;0").toCss()).isEmpty();
        assertThat(CssDeclarations.empty().with("--", "x;0", true).toCss(true, 1))
                .isEqualTo("\t0 !important;");
    }

    /** Found by the fuzz (t2-decl-important-order): important() lists the properties in the order they took the flag. */
    @Test
    void importantInFlagOrder() {
        CssDeclarations d = CssDeclarations.of(Map.of("width", "1px"))
                .with("top", "0")
                .with("top", "1", true)
                .with("width", "2", true)
                .with("top", "3")
                .with("top", "4", true);
        assertThat(d.declarations().keySet()).containsExactly("width", "top");
        assertThat(d.important()).containsExactly("width", "top");
        assertThat(d.with("width", "5", true).important()).containsExactly("width", "top");
    }

    @Test
    void equalityIsOrdered() {
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, String> ba = new LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");
        assertThat(CssDeclarations.of(ab)).isNotEqualTo(CssDeclarations.of(ba));
        assertThat(CssDeclarations.of(ab)).isNotEqualTo(CssDeclarations.of(ab).with("a", "1", true));
        assertThat(CssDeclarations.of(ab).without("b").without("a")).isEqualTo(CssDeclarations.empty());
    }

    /** DC3: the views are unmodifiable and of() copies its map. */
    @Test
    void immutableViewsAndCopies() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("color", "red");
        CssDeclarations d = CssDeclarations.of(source).with("top", "0", true);
        source.put("width", "1px");
        source.put("color", "blue");
        assertThat(d.declarations()).containsExactly(Map.entry("color", "red"), Map.entry("top", "0"));
        assertThat(d.toCss()).isEqualTo("color:red;top:0 !important;");
        assertThatThrownBy(() -> d.declarations().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> d.declarations().remove("color")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> d.important().add("color")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> d.important().remove("top")).isInstanceOf(UnsupportedOperationException.class);
        CssDeclarations.Builder builder = d.toBuilder();
        CssDeclarations built = builder.build();
        builder.add("height", "1px");
        assertThat(built).isEqualTo(d);
    }
}
