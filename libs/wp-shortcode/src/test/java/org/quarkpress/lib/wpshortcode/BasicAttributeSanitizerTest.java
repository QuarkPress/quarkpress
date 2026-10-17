package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;

class BasicAttributeSanitizerTest {

    private static void assertSanitized(String element, String token, String expected) {
        Optional<String> actual = BasicAttributeSanitizer.INSTANCE.sanitize(element, token);
        if (expected.isEmpty()) {
            assertThat(actual).as("%s %s", element, token).isEmpty();
        } else {
            assertThat(actual).as("%s %s", element, token).contains(expected);
        }
    }

    @Test
    void sanitizesLikeWpKsesOneAttr() { // R12.7
        assertSanitized("a", "title=\"x\"", "title=\"x\"");
        assertSanitized("a", " title = \"x\" ", " title=\"x\" ");
        assertSanitized("a", "title=x", "title=\"x\"");
        assertSanitized("a", "title='x'", "title='x'");
        assertSanitized("a", "title=\"x", "");
        assertSanitized("a", "title=\"", "title=\"\"");
        assertSanitized("a", "title=", "title=\"\"");
        assertSanitized("a", "title", "title");
        assertSanitized("a", "download", "download");
        assertSanitized("a", "download=\"d\"", "");
        assertSanitized("a", "onclick=\"x\"", "");
        assertSanitized("zz", "title=\"x\"", "");
        assertSanitized("A", "TITLE=\"x\"", "TITLE=\"x\"");
        assertSanitized("a", "href=\"javascript:x\"", "href=\"x\"");
        assertSanitized("a", "HREF=\"javascript:x\"", "HREF=\"x\"");
        assertSanitized("a", "href=\"http://a/?b=1&c=2\"", "href=\"http://a/?b=1&amp;c=2\"");
        assertSanitized("p", "data-a_b=\"1\"", "data-a_b=\"1\"");
        assertSanitized("p", "data-=\"1\"", "");
        assertSanitized("p", "data-a.b=\"1\"", "");
        // WordPress keeps style="color:red" (safecss_filter_attr); style is rejected here until lib-wp-kses (D4)
        assertSanitized("p", "style=\"color:red\"", "");
        assertSanitized("object", "type=\"Application/PDF\"", "type=\"Application/PDF\"");
        assertSanitized("object", "type=\"text/html\"", "");
        assertSanitized("a", "title=\"a\u0000b\u000bc\"", "title=\"abc\"");
        assertSanitized("a", "\ttitle=\"x\"\n", "\ttitle=\"x\"\n");
        assertSanitized("a", "title = = \"x\"", "title=\"= &quot;x&quot;\"");
        assertSanitized("a", "title=a=b", "title=\"a=b\"");
        assertSanitized("img", "src=\"&#106;avascript:x\"", "src=\"x\"");
        assertSanitized("a", "title=\"it's\"", "title=\"it&#039;s\"");
        assertSanitized("a", "title='say \"hi\"'", "title='say &quot;hi&quot;'");
    }

    @Test
    void objectDataIsRejected() { // D9: WordPress accepts PDFs on the site's upload host
        assertSanitized("object", "data=\"http://example.com/wp-content/uploads/a.pdf\"", "");
    }

    @Test
    void theAllowlistHasWordPresssShape() {
        SortedMap<String, List<String>> table = PostAllowlist.describe();
        assertThat(table).hasSize(124);
        assertThat(table.get("a")).contains("download(valueless)", "href", "data-*", "style", "title");
        assertThat(table.get("object")).contains("data(pdf-object)", "type(values:application/pdf)");
        assertThat(table.get("p")).isSorted();
        assertThat(table.firstKey()).isEqualTo("a");
    }
}
