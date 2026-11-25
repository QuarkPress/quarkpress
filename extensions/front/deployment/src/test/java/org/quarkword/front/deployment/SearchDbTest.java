package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkword.front.deployment.FrontCast.as;
import static org.quarkword.front.deployment.FrontCast.ids;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.front.FrontRequest;

/** The front end's search over the query reference's cast: relevance first, pages searched, protected posts hidden. */
class SearchDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(SearchDbTest.class, FrontCast.class);

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    static FrontCast cast;

    @BeforeEach
    void cast() {
        if (cast == null) {
            cast = new FrontCast(dataSource);
        }
    }

    @Test
    void titleMatchesComeFirstThenTheDate() throws Exception {
        FrontRequest red = cast.get("s=red");
        assertThat(red.status()).isEqualTo(200);
        assertThat(red.flags().search()).isTrue();
        assertThat(ids(red)).containsExactly(cast.p8, cast.p3);
        assertThat(ids(cast.get("s=red+car"))).containsExactly(cast.p3, cast.p8);
    }

    @Test
    void anonymousSearchHidesProtected() throws Exception {
        assertThat(ids(cast.get("s=secret"))).isEmpty();
        assertThat(ids(cast.get("s=secret", as(cast.erin)))).containsExactly(cast.p9);
        // drafts and private posts of others are never listed; the private post is listed for its reader
        assertThat(ids(cast.get("s=travel", as(cast.carol)))).isEmpty();
        assertThat(ids(cast.get("s=board"))).isEmpty();
        assertThat(ids(cast.get("s=board", as(cast.bob)))).containsExactly(cast.p6);
        // attachments are never listed (inherit is not a public status)
        assertThat(ids(cast.get("s=photo"))).isEmpty();
    }

    @Test
    void pagesAreSearched() throws Exception {
        assertThat(ids(cast.get("s=about"))).containsExactly(cast.about20);
    }

    @Test
    void anEmptySearchListsEveryPublishedPostAndPageNewestFirst() throws Exception {
        FrontRequest all = cast.get("s=");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.flags().search()).isTrue();
        assertThat(ids(all))
                .containsExactly(
                        cast.p10,
                        cast.p9,
                        cast.p8,
                        cast.p3,
                        cast.p2,
                        cast.p1,
                        cast.privacy24,
                        cast.home22,
                        cast.blog21,
                        cast.team23);
        assertThat(all.posts().found()).isEqualTo(12);
    }

    @Test
    void otherConditionsStillApply() throws Exception {
        assertThat(ids(cast.get("s=hello&author=" + cast.carol.value()))).isEmpty();
        assertThat(ids(cast.get("s=hello&year=2026"))).containsExactly(cast.p1);
    }
}
