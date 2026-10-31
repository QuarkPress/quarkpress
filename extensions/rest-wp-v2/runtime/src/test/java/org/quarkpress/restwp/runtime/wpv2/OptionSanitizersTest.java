package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.SettingDefinition;

/**
 * WordPress's per-option sanitizing on write. The expected values were read from WordPress 7.1.3 run as a black box
 * (its settings route on a single site, the stored option read back).
 */
class OptionSanitizersTest {

    @Test
    void sanitizationOutcomes() {
        assertThat(kept("blogname", "A & B <i>\"x\" 'y' &amp;"))
                .isEqualTo("A &amp; B &lt;i&gt;&quot;x&quot; &#039;y&#039; &amp;");
        assertThat(kept("date_format", "<b>F j, Y</b>")).isEqualTo("F j, Y");
        assertThat(kept("posts_per_page", 0)).isEqualTo(1);
        assertThat(kept("posts_per_page", -5)).isEqualTo(5);
        assertThat(kept("posts_per_page", -1)).isEqualTo(-1);
        assertThat(kept("start_of_week", -3)).isEqualTo(3);
        assertThat(rejected("timezone_string", "Mars/Base")).isTrue();
        assertThat(kept("timezone_string", "Europe/Paris")).isEqualTo("Europe/Paris");
        assertThat(rejected("WPLANG", "fr_FR")).isTrue();
        assertThat(rejected("admin_email", "nope")).isTrue();
        assertThat(rejected("siteurl", "ftp://x.test")).isTrue();
    }

    @Test
    void titlesAndTaglinesAreHtmlEscapedWithoutEncodingReferencesTwice() {
        assertThat(kept("blogname", "  Tom's  <b>x</b> é &amp; &nbsp; &foo; \n"))
                .isEqualTo("  Tom&#039;s  &lt;b&gt;x&lt;/b&gt; é &amp; &nbsp; &amp;foo; \n");
        assertThat(kept("blogdescription", "\"q\" 'a' <p>&copy;"))
                .isEqualTo("&quot;q&quot; &#039;a&#039; &lt;p&gt;&copy;");
        assertThat(kept("blogdescription", " a\tb ")).isEqualTo(" a\tb ");
    }

    @Test
    void dateAndTimeFormatsLoseTagsAsPhpStripsThem() {
        assertThat(kept("date_format", "  <b>Y</b>  m\n d ")).isEqualTo("  Y  m\n d ");
        assertThat(kept("date_format", "a<b")).isEqualTo("a");
        assertThat(kept("date_format", "<script>x</script>Y")).isEqualTo("xY");
        assertThat(kept("time_format", " <i>g</i>:i ")).isEqualTo(" g:i ");
    }

    @Test
    void identifiersAreAbsoluteIntegers() {
        assertThat(kept("start_of_week", 9)).isEqualTo(9);
        assertThat(kept("start_of_week", 0)).isEqualTo(0);
        assertThat(kept("default_category", -7)).isEqualTo(7);
        assertThat(kept("page_on_front", -2)).isEqualTo(2);
        assertThat(kept("page_for_posts", -3)).isEqualTo(3);
        assertThat(kept("site_icon", -4)).isEqualTo(4);
        assertThat(kept("start_of_week", "12")).isEqualTo(12);
        // the site logo has no sanitizing of its own
        assertThat(kept("site_logo", -5)).isEqualTo(-5);
        assertThat(kept("posts_per_page", -2)).isEqualTo(2);
        assertThat(kept("posts_per_page", -1000)).isEqualTo(1000);
        assertThat(kept("posts_per_page", 1000)).isEqualTo(1000);
    }

    @Test
    void commentAndPingStatusesCloseOnZeroOrEmpty() {
        assertThat(kept("default_comment_status", "0")).isEqualTo("closed");
        assertThat(kept("default_ping_status", "")).isEqualTo("closed");
        assertThat(kept("default_ping_status", "open")).isEqualTo("open");
        assertThat(kept("default_comment_status", "closed")).isEqualTo("closed");
    }

    @Test
    void timeZonesMustBeKnownIdentifiersExactlyAsWritten() {
        for (String zone :
                new String[] {"", "UTC", "Etc/UTC", "GMT", "EST5EDT", "US/Eastern", "Europe/Kiev", "Europe/Kyiv"}) {
            assertThat(kept("timezone_string", zone)).as(zone).isEqualTo(zone);
        }
        for (String zone : new String[] {"europe/paris", " Europe/Paris", "Europe/Paris ", "UTC+2", "SystemV/EST5"}) {
            assertThat(rejected("timezone_string", zone)).as(zone).isTrue();
        }
    }

    @Test
    void onlyAnEmptyLanguageIsKept() {
        assertThat(kept("WPLANG", "")).isEqualTo("");
        // PHP's empty("0"): kept like ""
        assertThat(kept("WPLANG", "0")).isEqualTo("0");
        assertThat(rejected("WPLANG", "en_US")).isTrue();
    }

    @Test
    void siteUrlsMustBeHttpAfterUrlSanitizing() {
        assertThat(kept("siteurl", "http://Example.test/a/")).isEqualTo("http://Example.test/a/");
        assertThat(kept("siteurl", "x.test")).isEqualTo("http://x.test");
        assertThat(kept("siteurl", " http://y.test ")).isEqualTo("http://y.test%20");
        assertThat(kept("siteurl", "HTTP://z.test")).isEqualTo("http://z.test");
        assertThat(kept("siteurl", "https://b.test/?q=a b&c=<d>")).isEqualTo("https://b.test/?q=a%20b&c=d");
        for (String url : new String[] {"", "/rel", "javascript:alert(1)", "ftp://x.test"}) {
            assertThat(rejected("siteurl", url)).as(url).isTrue();
        }
    }

    @Test
    void adminEmailMustBeAnAddress() {
        assertThat(kept("admin_email", "x@example.com")).isEqualTo("x@example.com");
        assertThat(kept("admin_email", "b..c@example.com")).isEqualTo("b..c@example.com");
        assertThat(rejected("admin_email", "a@b")).isTrue();
    }

    @Test
    void otherOptionsAreKeptAsGiven() {
        assertThat(kept("show_on_front", "foo")).isEqualTo("foo");
        assertThat(kept("default_post_format", "aside")).isEqualTo("aside");
        assertThat(kept("app_anything", "<b>")).isEqualTo("<b>");
    }

    @Test
    void booleansAreStoredAsPhpStoresThem() {
        assertThat(OptionSanitizers.stored(BooleanNode.TRUE)).isEqualTo(TextNode.valueOf("1"));
        assertThat(OptionSanitizers.stored(BooleanNode.FALSE)).isEqualTo(TextNode.valueOf(""));
        assertThat(OptionSanitizers.stored(IntNode.valueOf(5))).isEqualTo(IntNode.valueOf(5));
    }

    @Test
    void namesStartingWithQpAreRefusedAtRegistration() {
        assertThatThrownBy(() -> definition("qp_x", "blogname"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qp_x");
        assertThatThrownBy(() -> definition("x", "qp_features"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qp_features");
        assertThatThrownBy(() -> new SettingDefinition(
                        "x", "x", "date", Optional.empty(), JsonNodeFactory.instance.objectNode(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date");
        assertThat(definition("x", "y").restName()).isEqualTo("x");
    }

    private static SettingDefinition definition(String restName, String optionName) {
        return new SettingDefinition(
                restName, optionName, "string", Optional.empty(), JsonNodeFactory.instance.objectNode(), true);
    }

    private static Object kept(String option, Object value) {
        OptionSanitizers.Sanitized result = OptionSanitizers.sanitize(option, node(value));
        assertThat(result).as("%s %s", option, value).isInstanceOf(OptionSanitizers.Sanitized.Kept.class);
        JsonNode kept = ((OptionSanitizers.Sanitized.Kept) result).value();
        if (kept.isIntegralNumber() && kept.canConvertToInt()) {
            return kept.intValue();
        }
        return kept.isTextual() ? kept.textValue() : kept;
    }

    private static boolean rejected(String option, Object value) {
        return OptionSanitizers.sanitize(option, node(value)) instanceof OptionSanitizers.Sanitized.Rejected;
    }

    private static JsonNode node(Object value) {
        if (value instanceof Integer number) {
            return IntNode.valueOf(number);
        }
        return TextNode.valueOf((String) value);
    }
}
