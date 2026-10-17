package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.lib.wpshortcode.ShortcodeAttributes.Entry;
import org.quarkpress.lib.wpshortcode.ShortcodeAttributes.Key;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class ShortcodePropertyTest {

    /** Every main-profile tag renders the text it matched minus the escape brackets: lossless but visible. */
    private static final Shortcodes IDENTITY = Shortcodes.builder(identity()).build();

    private static ShortcodeRegistry identity() {
        ShortcodeRegistry.Builder b = ShortcodeRegistry.builder();
        for (String tag : ShortcodeArbitraries.MAIN_TAGS) {
            b.add(tag, (s, c) -> {
                ShortcodeMatch m = s.match();
                return m.whole()
                        .substring(
                                m.openEscape().length(),
                                m.whole().length() - m.closeEscape().length());
            });
        }
        return b.build();
    }

    @Test
    void applyAgreesWithParse() {
        Properties.check(
                "ShortcodePropertyTest.applyAgreesWithParse", 3000, ShortcodeArbitraries.plainContent(), content -> {
                    StringBuilder expected = new StringBuilder();
                    for (ShortcodeNode node : IDENTITY.parse(content)) {
                        expected.append(
                                switch (node) {
                                    case ShortcodeNode.Text t -> t.text();
                                    case ShortcodeNode.Escaped e -> Shortcodes.unwrap(e.match());
                                    case ShortcodeNode.Match m ->
                                        m.shortcode().match().whole();
                                });
                    }
                    assertThat(IDENTITY.apply(content)).isEqualTo(expected.toString());
                });
    }

    @Test
    void stripAgreesWithParse() { // replaces the spec's "no matches after strip" invariant (decision 11)
        Properties.check(
                "ShortcodePropertyTest.stripAgreesWithParse", 3000, ShortcodeArbitraries.plainContent(), content -> {
                    StringBuilder expected = new StringBuilder();
                    for (ShortcodeNode node : IDENTITY.parse(content)) {
                        expected.append(
                                switch (node) {
                                    case ShortcodeNode.Text t -> t.text();
                                    case ShortcodeNode.Escaped e -> Shortcodes.unwrap(e.match());
                                    case ShortcodeNode.Match m ->
                                        m.shortcode().match().openEscape()
                                                + m.shortcode().match().closeEscape();
                                });
                    }
                    assertThat(IDENTITY.strip(content)).isEqualTo(expected.toString());
                });
    }

    @Test
    void parseSourcesRebuildTheProtectedInput() {
        Properties.check(
                "ShortcodePropertyTest.parseSourcesRebuildTheProtectedInput",
                3000,
                ShortcodeArbitraries.content(),
                content -> {
                    StringBuilder joined = new StringBuilder();
                    IDENTITY.parse(content).forEach(node -> joined.append(node.source()));
                    assertThat(joined.toString()).isEqualTo(IDENTITY.protectForParse(content));
                });
    }

    @Test
    void nothingThrowsOnArbitraryText() {
        Gen<String> soup = Gens.oneOf(
                ShortcodeArbitraries.content(),
                Gens.strings(Gens.unicodeChars(), 0, 200),
                Gens.strings("[]/<>=\"' &#;𐀀ax", 0, 80));
        Shortcodes expanding = Shortcodes.builder(Registries.callbacks()).build();
        Properties.check("ShortcodePropertyTest.nothingThrowsOnArbitraryText", 3000, soup, content -> {
            for (Shortcodes s : List.of(IDENTITY, expanding)) {
                s.apply(content);
                s.apply(content, true);
                s.strip(content);
                s.has(content, "inner");
                s.tagsIn(content);
                s.parse(content);
                s.unautop(content);
            }
            ShortcodeAttributes.parse(content);
        });
    }

    @Test
    void attributeKeysAreUniqueAndReadBack() {
        Properties.check(
                "ShortcodePropertyTest.attributeKeysAreUniqueAndReadBack",
                3000,
                ShortcodeArbitraries.attributeText(),
                text -> {
                    ShortcodeAttributes attributes = ShortcodeAttributes.parse(text);
                    assertThat(attributes.entries().stream().map(Entry::key).toList())
                            .doesNotHaveDuplicates();
                    for (Entry e : attributes.entries()) {
                        Optional<String> read = e.key() instanceof Key.Index index
                                ? attributes.get(index.index())
                                : attributes.get(((Key.Name) e.key()).name());
                        assertThat(read).contains(e.value());
                    }
                });
    }
}
