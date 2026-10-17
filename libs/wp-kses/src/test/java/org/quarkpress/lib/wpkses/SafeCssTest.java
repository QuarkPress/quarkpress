package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SafeCssTest {

    private static final WordPressTables TABLES = WordPressTables.INSTANCE;

    private static SafeCss css(List<String> properties, CssAllowFilter filter) {
        return new SafeCss(properties, filter, TABLES.protocols());
    }

    @Test
    void matchesWordPress() {
        Examples.assertFile("safecss.json");
    }

    @Test
    void anAddedPropertyIsComparedExactly() {
        // Verified against WordPress: a safe_style_css filter adding COLOR keeps "COLOR:red".
        List<String> properties = new ArrayList<>(TABLES.safeStyleProperties());
        properties.add("COLOR");
        assertThat(css(properties, null).filter("COLOR:red")).isEqualTo("COLOR:red");
    }

    @Test
    void theAllowFilterSeesTestStringsOfAllowedPropertiesOnly() {
        // Verified against WordPress: a filter returning true saw "color: rgb(1,2,3)" and "width:  url(x)"; foo was
        // dropped before it was asked.
        List<String> seen = new ArrayList<>();
        SafeCss filtered = css(TABLES.safeStyleProperties(), (allowed, test) -> {
            seen.add(test);
            return true;
        });
        assertThat(filtered.filter("color: rgb(1,2,3); foo: x(1); width: calc(1px) url(x)"))
                .isEqualTo("color: rgb(1,2,3);width: calc(1px) url(x)");
        assertThat(seen).containsExactly("color: rgb(1,2,3)", "width:  url(x)");
    }

    @Test
    void anEmptyListTurnsFilteringOff() {
        assertThat(css(List.of(), null).filter("color:red; behavior:url(javascript:x)\n"))
                .isEqualTo("color:red; behavior:url(javascript:x)");
    }

    @Test
    void removalReplacesEveryOccurrenceOfEachText() {
        // WordPress drops this declaration: removing the first gradient's text everywhere breaks the second.
        assertThat(css(TABLES.safeStyleProperties(), null)
                        .filter("background: linear-gradient(y) linear-gradient(x linear-gradient(y) z)"))
                .isEmpty();
    }

    @Test
    void laterDuplicateMatchesRemoveTextCreatedByEarlierRemovals() {
        // Verified against WordPress (str_replace runs once per match, duplicates included).
        SafeCss filter = css(TABLES.safeStyleProperties(), null);
        assertThat(filter.filter("background:calc(uurl(a(b)rl(a(b))&) url(a(b)"))
                .isEmpty();
        assertThat(filter.filter("background-image:var(uurl((x)rl((x))/*) url((x)"))
                .isEmpty();
        assertThat(filter.filter("--x:url(z) var(uurl(a(b)rl(a(b))/*) url(a(b)"))
                .isEmpty();
        assertThat(filter.filter("--x:url(z) calc(uurl(a(b)rl(a(b))=) url(a(b)"))
                .isEmpty();
        String joined = "background:uurl(x)rl(x) url(x)";
        assertThat(filter.filter(joined)).isEqualTo(joined);
        String gradients = "background:llinear-gradient(a(b))inear-gradient(a(b)) linear-gradient(a(b))";
        assertThat(filter.filter(gradients)).isEqualTo(gradients);
    }

    @Test
    void manyDistinctTextsAreRemovedBySpan() {
        // Expected values come from a ruling: past the exact-removal bound only matched spans are removed
        // (documented difference), and a declaration where a removed text survives is dropped. WordPress also
        // drops the second declaration, which exact removal would keep.
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < SafeCss.EXACT_REMOVAL_LIMIT + 5; i++) {
            many.append(" url(a").append(i).append(".png)");
        }
        SafeCss filter = css(TABLES.safeStyleProperties(), null);
        String plain = "background-image:" + many;
        assertThat(filter.filter(plain)).isEqualTo(plain);
        assertThat(filter.filter(plain + " calc(uurl(a(b)rl(a(b))&) url(a(b)")).isEmpty();
    }

    @Test
    void functionsNestToAnyDepth() {
        // Expected value comes from a ruling: WordPress's recursive pattern fails at PCRE limits; the Java
        // scanner has none, so any depth of balanced parentheses is stripped.
        String deep = "width: calc(" + "(".repeat(20_000) + "1px" + ")".repeat(20_000) + ")";
        assertThat(css(TABLES.safeStyleProperties(), null).filter(deep)).isEqualTo(deep);
    }

    @Test
    void hostileDeclarationsStayLinear() {
        String[][] cases = {
            {"background: " + "linear-gradient(".repeat(200_000), ""},
            {"background: " + "url(".repeat(300_000), ""},
            {"width: " + "calc(".repeat(300_000), ""},
            {
                "background: " + "linear-gradient(a) ".repeat(100_000) + "x",
                "background: " + "linear-gradient(a) ".repeat(100_000) + "x"
            },
            {";".repeat(1_000_000), ""},
            {
                "color:red; width: calc(" + "a".repeat(2_000_000) + ")",
                "color:red;width: calc(" + "a".repeat(2_000_000) + ")"
            }
        };
        SafeCss filter = css(TABLES.safeStyleProperties(), null);
        for (String[] c : cases) {
            String result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> filter.filter(c[0]));
            assertThat(result).isEqualTo(c[1]);
        }
    }
}
