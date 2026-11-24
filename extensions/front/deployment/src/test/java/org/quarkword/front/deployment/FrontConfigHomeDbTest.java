package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * On a single site the {@code quarkword.front.home} and {@code quarkword.front.siteurl} settings override the stored
 * options (WordPress's {@code WP_HOME} and {@code WP_SITEURL}), their trailing slash removed; and a declared setting
 * such as {@code cache-control} is accepted at start.
 */
class FrontConfigHomeDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(FrontConfigHomeDbTest.class, ProbeResource.class)
            .overrideConfigKey("quarkword.front.home", "http://cfg.test/")
            .overrideConfigKey("quarkword.front.siteurl", "https://cfg.test/wp/")
            .overrideConfigKey("quarkword.front.cache-control", "public, max-age=60");

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Test
    void theSettingsWinOverTheOptions() {
        TestData.textOption(dataSource, TestData.MAIN, "home", "http://front.test");
        TestData.textOption(dataSource, TestData.MAIN, "siteurl", "http://front.test/wp");
        TestData.textOption(dataSource, TestData.MAIN, "permalink_structure", "/%postname%/");
        assertThat(Http.at("front.test", null)
                        .get("/probe")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .isEqualTo("http://cfg.test|http://cfg.test/|http://cfg.test/?p=1|http://cfg.test/wp"
                        + "|https://cfg.test/wp/wp-admin/|https://cfg.test/wp/wp-login.php"
                        + "|http://cfg.test/wp-json/wp/v2/posts|");
    }

    @Test
    void theCacheControlSettingIsRead() {
        assertThat(Http.at("front.test", null)
                        .get("/probe/cache-control")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .isEqualTo("public, max-age=60");
    }
}
