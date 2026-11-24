package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The structure tags of a link, substituted as WordPress's {@code get_permalink} substitutes them. */
class TagValuesTest {

    private static final String DEFAULT = "/%year%/%monthnum%/%day%/%postname%/";

    private static Map<String, String> post1() {
        Map<String, String> values = new HashMap<>(TagValues.date(LocalDateTime.of(2025, 11, 20, 10, 15, 30)));
        values.put("postname", "hello-world");
        values.put("pagename", "hello-world");
        values.put("post_id", "1");
        values.put("author", "alice");
        values.put("category", "uncategorized");
        return values;
    }

    @Test
    void theDefaultStructureWithPost1() {
        assertThat(TagValues.substitute(DEFAULT, post1(), false)).isEqualTo("/2025/11/20/hello-world/");
        assertThat(TagValues.substitute("/%post_id%/%postname%/", post1(), false))
                .isEqualTo("/1/hello-world/");
        assertThat(TagValues.substitute("/%author%/%postname%/", post1(), false))
                .isEqualTo("/alice/hello-world/");
        assertThat(TagValues.substitute("/%category%/%postname%/", post1(), false))
                .isEqualTo("/uncategorized/hello-world/");
        assertThat(TagValues.substitute("/%hour%%minute%%second%/%postname%", post1(), false))
                .isEqualTo("/101530/hello-world");
    }

    @Test
    void leaveNameKeepsTheNameTagsLiteral() {
        Map<String, String> values = new HashMap<>(post1());
        values.putAll(TagValues.date(LocalDateTime.of(2025, 11, 23, 9, 0, 0)));
        assertThat(TagValues.substitute(DEFAULT, values, true)).isEqualTo("/2025/11/23/%postname%/");
        assertThat(TagValues.substitute("%pagename%", values, true)).isEqualTo("%pagename%");
        assertThat(TagValues.substitute("/%author%/%pagename%/", values, true)).isEqualTo("/alice/%pagename%/");
    }

    @Test
    void dateValuesAreZeroPadded() {
        Map<String, String> march = TagValues.date(LocalDateTime.of(2025, 3, 7, 8, 5, 9));
        assertThat(march)
                .containsEntry("year", "2025")
                .containsEntry("monthnum", "03")
                .containsEntry("day", "07")
                .containsEntry("hour", "08")
                .containsEntry("minute", "05")
                .containsEntry("second", "09");
        assertThat(TagValues.substitute("/%year%/%monthnum%/", march, false)).isEqualTo("/2025/03/");
        assertThat(TagValues.date(LocalDateTime.of(999, 12, 31, 23, 59, 59))).containsEntry("year", "0999");
    }

    @Test
    void anUnknownTagAndAnEmptyValueStayAsTheyAre() {
        Map<String, String> values = new HashMap<>(post1());
        values.put("author", "");
        assertThat(TagValues.substitute("/%author%/%year%/%postname%/", values, false))
                .isEqualTo("//2025/hello-world/");
        assertThat(TagValues.substitute("/%genre%/%postname%/", values, false)).isEqualTo("/%genre%/hello-world/");
        assertThat(TagValues.substitute("/100%/%postname%/", values, false)).isEqualTo("/100%/hello-world/");
        assertThat(TagValues.substitute("", values, false)).isEmpty();
    }
}
