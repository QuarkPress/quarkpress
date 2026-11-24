package org.quarkword.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkword.core.PostTypeDefinition;

class QueryVarsTest {

    @Test
    void aGivenQueryVarIsSlugifiedAsWordPressDoes() {
        PostTypeDefinition shelf = PostTypeDefinition.builder("book")
                .publicly(true)
                .queryVar("Book Shelf")
                .build();
        assertThat(QueryVars.slugified(shelf).queryVar()).isEqualTo(Optional.of("book-shelf"));
        assertThat(QueryVars.slugified(shelf).rewrite()).isEqualTo(shelf.rewrite());
        PostTypeDefinition dotted = PostTypeDefinition.builder("book")
                .publicly(true)
                .queryVar("Mood.Var")
                .build();
        assertThat(QueryVars.slugified(dotted).queryVar()).isEqualTo(Optional.of("mood-var"));
    }

    @Test
    void aSlugOrAMissingQueryVarIsLeftAlone() {
        PostTypeDefinition book =
                PostTypeDefinition.builder("book").publicly(true).build();
        assertThat(QueryVars.slugified(book)).isSameAs(book);
        PostTypeDefinition formats = PostTypeDefinition.builder("book")
                .publicly(true)
                .queryVar("post_format")
                .build();
        assertThat(QueryVars.slugified(formats)).isEqualTo(formats);
        for (PostTypeDefinition type : BuiltInPostTypes.all()) {
            assertThat(QueryVars.slugified(type)).isSameAs(type);
        }
    }

    @Test
    void aQueryVarThatSlugifiesToNothingIsRefused() {
        PostTypeDefinition bang = PostTypeDefinition.builder("book")
                .publicly(true)
                .queryVar("!!!")
                .build();
        assertThatThrownBy(() -> QueryVars.slugified(bang))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("book")
                .hasMessageContaining("!!!");
    }
}
