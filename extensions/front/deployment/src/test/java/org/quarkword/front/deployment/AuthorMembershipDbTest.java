package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.UserId;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.QueriedObject;

/**
 * In a network, an author archive without posts is a 200 only for a member of the site (front-facts FQ-G17 and G5:
 * a network user of no site is a 404 by id and by nicename).
 */
class AuthorMembershipDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.multisite(AuthorMembershipDbTest.class, FrontCast.class);

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    static FrontCast cast;
    static UserId outsider;

    @BeforeEach
    void cast() {
        if (cast == null) {
            cast = new FrontCast(dataSource);
            outsider = TestData.profile(dataSource, "olga", "olga-" + cast.site.value());
        }
    }

    @Test
    void aMemberWithoutPostsIsAnEmptyArchive() throws Exception {
        FrontRequest erin = cast.get("author=" + cast.erin.value());
        assertThat(erin.status()).isEqualTo(200);
        assertThat(erin.posts().posts()).isEmpty();
        assertThat(cast.get("author_name=erin-" + cast.site.value()).status()).isEqualTo(200);
    }

    @Test
    void aUserOfNoSiteIsNotFound() throws Exception {
        FrontRequest byId = cast.get("author=" + outsider.value());
        assertThat(byId.status()).isEqualTo(404);
        assertThat(cast.get("author_name=olga-" + cast.site.value()).status()).isEqualTo(404);
        // the user exists and stays the queried object, as WordPress's 404 keeps it: the 404 is the membership's
        assertThat(byId.queriedObject()).containsInstanceOf(QueriedObject.Author.class);
    }
}
