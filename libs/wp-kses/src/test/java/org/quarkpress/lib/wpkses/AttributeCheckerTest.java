package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttributeCheckerTest {

    private static final String UPLOAD = "https://example.org/wp-content/uploads/2026/10";

    /** CSS results for the style tests below, as WordPress's safecss_filter_attr gives them (R9.5). */
    private static final Map<String, String> CSS = Map.of(
            "font-family: 'Open Sans'", "font-family: 'Open Sans'",
            "font-family: 'Open Sans'; foo: bar", "font-family: 'Open Sans'",
            "foo:bar", "",
            "0", "0");

    private static final AttributeChecker.Environment ENV =
            new AttributeChecker.Environment(CSS::get, PdfObjects.policy(UPLOAD));

    private static AttributeChecker.Result check(String name, String value, ElementRule element) {
        boolean valueless = value == null;
        String whole = valueless ? name : name + "=\"" + value + "\"";
        return AttributeChecker.check(name, valueless ? "" : value, whole, valueless, element, ENV);
    }

    private static ElementRule rule(String name, AttributeRule rule) {
        return ElementRule.noAttributes().withAttribute(name, rule);
    }

    @Test
    void namesAreLookedUpInLowercase() {
        ElementRule a = rule("title", AttributeRule.allowed());
        assertThat(check("TITLE", "t", a).accepted()).isTrue();
        assertThat(check("href", "t", a).accepted()).isFalse();
        assertThat(check("href", "t", a)).isEqualTo(AttributeChecker.Result.REJECTED);
    }

    @Test
    void dataWildcardAcceptsOnlyAsciiLowercaseNames() {
        ElementRule p = rule("data-*", AttributeRule.allowed());
        assertThat(check("data-foo", "1", p).accepted()).isTrue();
        assertThat(check("DATA-FOO", "1", p).accepted()).isTrue();
        assertThat(check("data-x_y", "1", p).accepted()).isTrue();
        assertThat(check("data-", "1", p).accepted()).isFalse();
        assertThat(check("data-ä", "1", p).accepted()).isFalse();
        assertThat(check("data-foo.bar", "1", p).accepted()).isFalse();
        assertThat(check("data-foo:bar", "1", p).accepted()).isFalse();
        assertThat(check("aria-foo", "1", p).accepted()).isFalse();
    }

    @Test
    void lengthsCountUtf8BytesOfTheReencodedValue() {
        ElementRule a = rule("x", AttributeRule.of(AttributeCheck.maxLength(3)));
        assertThat(check("x", "abc", a).accepted()).isTrue();
        assertThat(check("x", "abcd", a).accepted()).isFalse();
        assertThat(check("x", "&amp;", a).accepted()).isFalse();
        assertThat(check("x", "éé", a).accepted()).isFalse();
        ElementRule min = rule("x", AttributeRule.of(AttributeCheck.minLength(2)));
        assertThat(check("x", "a", min).accepted()).isFalse();
        assertThat(check("x", "ab", min).accepted()).isTrue();
    }

    @Test
    void smallNumbersFollowWordPressPattern() {
        // Harness-verified values (R9.6 and probes): up to six spaces, one to six digits, up to six spaces,
        // plus one final line feed.
        assertThat(AttributeChecker.isSmallNumber("10")).isTrue();
        assertThat(AttributeChecker.isSmallNumber(" 7 ")).isTrue();
        assertThat(AttributeChecker.isSmallNumber("7\n")).isTrue();
        assertThat(AttributeChecker.isSmallNumber("7      \n")).isTrue();
        assertThat(AttributeChecker.isSmallNumber("7       \n")).isFalse();
        assertThat(AttributeChecker.isSmallNumber("7\n\n")).isTrue();
        assertThat(AttributeChecker.isSmallNumber("-1")).isFalse();
        assertThat(AttributeChecker.isSmallNumber("1.5")).isFalse();
        assertThat(AttributeChecker.isSmallNumber("0000001")).isFalse();
        assertThat(AttributeChecker.isSmallNumber("1234567")).isFalse();
        assertThat(AttributeChecker.isSmallNumber("abc")).isFalse();
        ElementRule a = rule("x", AttributeRule.of(AttributeCheck.maxValue(10)));
        assertThat(check("x", "10", a).accepted()).isTrue();
        assertThat(check("x", "11", a).accepted()).isFalse();
        ElementRule min = rule("x", AttributeRule.of(AttributeCheck.minValue(5)));
        assertThat(check("x", "5", min).accepted()).isTrue();
        assertThat(check("x", "4", min).accepted()).isFalse();
    }

    @Test
    void valuelessAndValuesChecks() {
        ElementRule y = rule("x", AttributeRule.of(AttributeCheck.valueless("y")));
        assertThat(check("x", null, y).accepted()).isTrue();
        assertThat(check("x", "", y).accepted()).isFalse();
        ElementRule n = rule("x", AttributeRule.of(AttributeCheck.valueless("N")));
        assertThat(check("x", null, n).accepted()).isFalse();
        assertThat(check("x", "", n).accepted()).isTrue();
        ElementRule values = rule("x", AttributeRule.of(AttributeCheck.values(List.of("one", "two", "Three"))));
        assertThat(check("x", "ONE", values)).isEqualTo(new AttributeChecker.Result(true, "ONE", "x=\"ONE\""));
        assertThat(check("x", "three", values).accepted()).isFalse();
        assertThat(check("x", "Three", values).accepted()).isFalse();
        assertThat(check("x", " one", values).accepted()).isFalse();
    }

    @Test
    void callbackAndPdfChecks() {
        ElementRule cb = rule("x", AttributeRule.of(AttributeCheck.callback("ok"::equals)));
        assertThat(check("x", "ok", cb).accepted()).isTrue();
        assertThat(check("x", "no", cb).accepted()).isFalse();
        ElementRule pdf = rule("data", AttributeRule.required(AttributeCheck.pdfObjectUrl()));
        assertThat(check("data", UPLOAD + "/a.pdf", pdf).accepted()).isTrue();
        assertThat(check("data", "https://evil.org/a.pdf", pdf).accepted()).isFalse();
    }

    @Test
    void pdfPolicyMatchesWordPress() {
        // _wp_kses_allow_pdf_objects with the harness upload URL (probe, PHP 8.4).
        var policy = PdfObjects.policy(UPLOAD);
        assertThat(policy.test("https://example.org/wp-content/uploads/2026/10/a.pdf"))
                .isTrue();
        assertThat(policy.test("http://example.org/x.pdf")).isTrue();
        assertThat(policy.test("https://example.org/a.PDF")).isFalse();
        assertThat(policy.test("https://example.org/a.pdf?x=1")).isFalse();
        assertThat(policy.test("https://example.org/a.pdf#p")).isFalse();
        assertThat(policy.test("https://example.org.evil/a.pdf")).isFalse();
        assertThat(policy.test("https://example.org:8080/a.pdf")).isFalse();
        assertThat(policy.test("//example.org/a.pdf")).isFalse();
        assertThat(policy.test("https://EXAMPLE.org/a.pdf")).isFalse();
        assertThat(PdfObjects.policy(null).test(UPLOAD + "/a.pdf")).isFalse();
        assertThat(PdfObjects.policy("https://h:8080/u").test("https://h:8080/a.pdf"))
                .isTrue();
    }

    @Test
    void styleIsFilteredAndReescapedOnlyWhenItChanges() {
        ElementRule p = rule("style", AttributeRule.allowed());
        // unchanged: kses's own encoding stays (R9.5 step 4)
        assertThat(check("style", "font-family: &apos;Open Sans&apos;", p))
                .isEqualTo(new AttributeChecker.Result(
                        true, "font-family: &apos;Open Sans&apos;", "style=\"font-family: &apos;Open Sans&apos;\""));
        // changed: esc_attr encoding (R9.5 step 3)
        assertThat(check("style", "font-family: &apos;Open Sans&apos;; foo: bar", p))
                .isEqualTo(new AttributeChecker.Result(
                        true, "font-family: &#039;Open Sans&#039;", "style=\"font-family: &#039;Open Sans&#039;\""));
        assertThat(check("style", "foo:bar", p).accepted()).isFalse();
        assertThat(check("style", "0", p).accepted()).isFalse();
    }

    // Expected values below are WordPress's own results for a custom allowlist (kses harness probe, 7.1.3, PHP 8.4):
    // a falsy "data-*" value (false, 0, "0", "", null, an empty array) allows no data- attribute, while an exact
    // attribute name wins over "data-*". The typed model has no falsy values: AllowlistJson (Task 11) leaves the
    // "data-*" key out, which is the case tested here.
    @Test
    void dataAttributesNeedTheWildcardKeyAndExactNamesWin() {
        ElementRule none = ElementRule.noAttributes().withAttribute("title", AttributeRule.allowed());
        assertThat(check("data-foo", "abcdef", none).accepted()).isFalse();
        ElementRule limited = ElementRule.noAttributes()
                .withAttribute("data-foo", AttributeRule.allowed())
                .withAttribute("data-*", AttributeRule.of(AttributeCheck.maxLength(2)));
        assertThat(check("data-foo", "abcdef", limited).accepted()).isTrue();
        assertThat(check("data-bar", "abcdef", limited).accepted()).isFalse();
        assertThat(check("data-bar", "ab", limited).accepted()).isTrue();
    }
}
