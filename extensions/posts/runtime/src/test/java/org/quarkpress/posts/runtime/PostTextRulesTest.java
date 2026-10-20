package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostTextRulesTest {

    @Test
    void windowsCodePageEntitiesBecomeTheirUnicodeEntities() {
        assertThat(PostTextRules.convertInvalidEntities("a &#150; b")).isEqualTo("a &#8211; b");
        assertThat(PostTextRules.convertInvalidEntities("&#128;&#129;&#159;")).isEqualTo("&#8364;&#376;");
        assertThat(PostTextRules.convertInvalidEntities("&#160; &#15; &#1500;")).isEqualTo("&#160; &#15; &#1500;");
        assertThat(PostTextRules.convertInvalidEntities("&#150")).isEqualTo("&#150");
    }
}
