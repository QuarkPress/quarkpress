package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.math.BigInteger;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.Upload;
import org.quarkpress.media.runtime.UploadLimits;
import org.quarkpress.persistence.NetworkOptions;

/**
 * The upload limit the media library tells the editor ({@code wp_max_upload_size}): the smaller of the configured
 * maximum and the HTTP body limit, and on a network also the per-file limit of {@code fileupload_maxk} (as WordPress's
 * multisite filter applies it, whether or not the network's checks are on) and, with the checks on, the space left on
 * the site; no capability is needed to ask.
 */
class MaxUploadSizeDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    MaxUploadSizeDbTest.class,
                    TestStorage.class,
                    TestProcessor.class,
                    TestMultisiteMode.class,
                    NetworkUploadFixtures.class)
            .overrideConfigKey("quarkpress.media.max-upload-size", "2M")
            .overrideConfigKey("quarkus.http.limits.max-body-size", "10M");

    static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    static final long TEN_MB = 10 * 1_048_576L;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    NetworkOptions network;

    NetworkUploadFixtures saved;
    SiteId site;
    UserId erin;
    UserId frank;

    @BeforeEach
    void setUp() {
        saved = NetworkUploadFixtures.save(network);
        site = TestData.site(dataSource);
        erin = TestData.profile(dataSource, "erin");
        TestData.member(dataSource, site, erin, "[\"subscriber\"]");
        frank = TestData.profile(dataSource, "frank");
        TestData.member(dataSource, site, frank, "[\"administrator\"]");
    }

    @AfterEach
    void restoreTheNetwork() {
        saved.restore();
    }

    private long maxUploadSize() throws Exception {
        return Identities.onSite(site, Identities.user(erin), () -> media.maxUploadSize());
    }

    private static MemorySize bytes(long value) {
        return new MemorySize(BigInteger.valueOf(value));
    }

    @Test
    void theSmallerLimitWins() throws Exception {
        // a per-file network limit above both leaves the configured 2M against the body limit of 10M
        network.set("fileupload_maxk", JSON.textNode("4000"));
        assertThat(maxUploadSize()).isEqualTo(2_097_152L);
        // without a configured maximum the body limit (Quarkus's default 10240K) is the answer
        assertThat(UploadLimits.smallest(Optional.empty(), bytes(TEN_MB), Optional.empty()))
                .isEqualTo(10_485_760L);
        assertThat(UploadLimits.smallest(Optional.of(bytes(2_097_152L)), bytes(TEN_MB), Optional.empty()))
                .isEqualTo(2_097_152L);
        assertThat(UploadLimits.smallest(Optional.of(bytes(20 * 1_048_576L)), bytes(TEN_MB), Optional.empty()))
                .isEqualTo(10_485_760L);
    }

    @Test
    void networksCapByFileuploadMaxk() throws Exception {
        network.set("upload_space_check_disabled", JSON.textNode("0"));
        network.set("fileupload_maxk", JSON.textNode("1500"));
        assertThat(maxUploadSize()).isEqualTo(1_536_000L);
        // the per-file limit caps the editor's number even while the network's checks are off (WordPress's filter)
        network.set("upload_space_check_disabled", JSON.textNode("1"));
        assertThat(maxUploadSize()).isEqualTo(1_536_000L);
        // the option as a number reads the same
        network.set("fileupload_maxk", JSON.numberNode(1000));
        assertThat(maxUploadSize()).isEqualTo(1_024_000L);
        // the network's default per-file limit is 1500 KB
        network.delete("fileupload_maxk");
        assertThat(maxUploadSize()).isEqualTo(1_536_000L);
        // the KB value is read as a whole number before the multiplication, as WordPress's filter casts it
        network.set("fileupload_maxk", JSON.textNode("1500.9"));
        assertThat(maxUploadSize()).isEqualTo(1_536_000L);
    }

    @Test
    void withTheChecksOnTheSpaceLeftCapsToo() throws Exception {
        network.set("upload_space_check_disabled", JSON.textNode("0"));
        network.set("fileupload_maxk", JSON.textNode("1500"));
        network.set("blog_upload_space", JSON.textNode("1"));
        long stored = 300 * 1024;
        Identities.onSite(
                site,
                Identities.user(frank),
                () -> media.store(Upload.builder("stored.txt", NetworkUploadFixtures.text("max-upload-size", stored))
                        .declaredType("text/plain")
                        .build()));
        assertThat(maxUploadSize()).isEqualTo(1_048_576L - stored);
        // the site's own quota overrides the network's
        TestData.siteOption(dataSource, site, "blog_upload_space", "\"2\"");
        assertThat(maxUploadSize()).isEqualTo(1_536_000L);
        TestData.siteOption(dataSource, site, "blog_upload_space", "\"0\"");
        assertThat(maxUploadSize()).isEqualTo(0L);
        // with the checks off the space left plays no part
        TestData.siteOption(dataSource, site, "blog_upload_space", "\"1\"");
        network.set("upload_space_check_disabled", JSON.textNode("1"));
        assertThat(maxUploadSize()).isEqualTo(1_536_000L);
    }
}
