package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteId;

/** On multisite the {@code quarkword.front.home} and {@code siteurl} settings are ignored: each site's options win. */
class FrontConfigHomeMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.multisite(
                    FrontConfigHomeMultisiteDbTest.class, ProbeResource.class)
            .overrideConfigKey("quarkword.front.home", "http://cfg.test/")
            .overrideConfigKey("quarkword.front.siteurl", "http://cfg.test/wp/");

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Test
    void theOptionsWinOverTheSettings() {
        SiteId site = TestData.site(dataSource);
        String domain = TestData.domain(dataSource, site);
        assertThat(Http.at(domain, null)
                        .get("/probe")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .isEqualTo("http://" + domain + "|http://" + domain + "/|http://" + domain + "/?p=1|http://" + domain
                        + "|http://" + domain + "/wp-admin/|http://" + domain + "/wp-login.php|http://" + domain
                        + "/wp-json/wp/v2/posts|");
    }
}
