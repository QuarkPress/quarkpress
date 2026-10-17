package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class WordPressTablesTest {

    private static final WordPressTables TABLES = WordPressTables.INSTANCE;

    private static final List<String> GLOBALS = List.of(
            "aria-controls",
            "aria-current",
            "aria-describedby",
            "aria-details",
            "aria-expanded",
            "aria-hidden",
            "aria-label",
            "aria-labelledby",
            "aria-live",
            "class",
            "data-*",
            "dir",
            "hidden",
            "id",
            "lang",
            "style",
            "tabindex",
            "title",
            "role",
            "xml:lang");

    @Test
    void protocolsAndUriAttributesAreWordPressLists() {
        assertThat(TABLES.protocols())
                .containsExactly(
                        "http", "https", "ftp", "ftps", "mailto", "news", "irc", "irc6", "ircs", "gopher", "nntp",
                        "feed", "telnet", "mms", "rtsp", "sms", "svn", "tel", "fax", "xmpp", "webcal", "urn");
        assertThat(TABLES.uriAttributes())
                .containsExactly(
                        "action",
                        "archive",
                        "background",
                        "cite",
                        "classid",
                        "codebase",
                        "data",
                        "formaction",
                        "href",
                        "icon",
                        "longdesc",
                        "manifest",
                        "poster",
                        "profile",
                        "src",
                        "usemap",
                        "xmlns");
    }

    @Test
    void safeStyleListKeepsWordPressDuplicates() {
        assertThat(TABLES.safeStyleProperties())
                .hasSize(191)
                .startsWith("background")
                .endsWith("--*");
        assertThat(new HashSet<>(TABLES.safeStyleProperties())).hasSize(186);
    }

    @Test
    void postTableHas124ElementsEachWithTheGlobalAttributes() {
        assertThat(TABLES.post().elements()).hasSize(124);
        TABLES.post()
                .elements()
                .forEach((name, rule) ->
                        assertThat(rule.attributes().keySet()).as(name).containsAll(GLOBALS));
        assertThat(TABLES.post().allows("script")).isFalse();
        assertThat(TABLES.post().allows("form")).isFalse();
        assertThat(TABLES.post().allows("mi")).isTrue();
    }

    @Test
    void postTableHasWordPressSpecialRules() {
        ElementRule a = TABLES.post().element("a").orElseThrow();
        assertThat(List.copyOf(a.attributes().keySet())).startsWith("href", "rel", "rev", "name", "target", "download");
        assertThat(a.attribute("download").orElseThrow()).isEqualTo(AttributeRule.of(AttributeCheck.valueless("y")));
        ElementRule object = TABLES.post().element("object").orElseThrow();
        assertThat(object.attribute("data").orElseThrow())
                .isEqualTo(AttributeRule.required(AttributeCheck.pdfObjectUrl()));
        assertThat(object.attribute("type").orElseThrow())
                .isEqualTo(AttributeRule.required(AttributeCheck.values(List.of("application/pdf"))));
    }

    @Test
    void dataTableIsTheSmallList() {
        assertThat(TABLES.data().elements().keySet())
                .containsExactly(
                        "a",
                        "abbr",
                        "acronym",
                        "b",
                        "blockquote",
                        "cite",
                        "code",
                        "del",
                        "em",
                        "i",
                        "q",
                        "s",
                        "strike",
                        "strong");
        assertThat(TABLES.data().element("a").orElseThrow().attributes().keySet())
                .containsExactly("href", "title");
        assertThat(TABLES.data().element("b").orElseThrow().attributes()).isEmpty();
    }

    @Test
    void unknownLinesAreRejected() {
        assertThatThrownBy(() -> WordPressTables.parse(List.of("bogus line")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WordPressTables.parse(List.of("protocols http")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
    }
}
