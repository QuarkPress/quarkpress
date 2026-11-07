package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.persistence.NetworkOptions;

/** The configuration turns the network's upload limits off whatever the network's own option says. */
class NetworkLimitsOffDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    NetworkLimitsOffDbTest.class,
                    TestStorage.class,
                    TestProcessor.class,
                    TestMultisiteMode.class,
                    NetworkUploadFixtures.class)
            .overrideConfigKey("quarkpress.media.network-limits.enabled", "false");

    static final long KB = 1024;
    static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    NetworkOptions network;

    @Inject
    TestStorage storage;

    @Inject
    TestClock clock;

    NetworkUploadFixtures saved;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-05T10:00:00Z"));
        storage.clear();
        saved = NetworkUploadFixtures.save(network);
    }

    @AfterEach
    void restoreTheNetwork() {
        saved.restore();
    }

    @Test
    void theConfigurationWinsOverTheNetworkOption() throws Exception {
        network.set("upload_space_check_disabled", JSON.textNode("0"));
        network.set("blog_upload_space", JSON.textNode("1"));
        network.set("fileupload_maxk", JSON.textNode("100"));
        SiteId site = TestData.site(dataSource);
        TestData.siteOption(dataSource, site, "timezone_string", "\"UTC\"");
        UserId frank = TestData.profile(dataSource, "frank");
        TestData.member(dataSource, site, frank, "[\"administrator\"]");
        for (String name : List.of("stored.txt", "more.txt")) {
            long size = name.equals("stored.txt") ? 900 * KB : 200 * KB;
            MediaSnapshot stored = Identities.onSite(
                    site,
                    Identities.user(frank),
                    () -> media.store(Upload.builder(name, NetworkUploadFixtures.text("network-limits-off", size))
                            .declaredType("text/plain")
                            .build()));
            assertThat(stored.fileSize()).isEqualTo(size);
        }
    }
}
