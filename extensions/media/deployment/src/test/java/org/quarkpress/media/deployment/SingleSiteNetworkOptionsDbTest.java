package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.Upload;
import org.quarkpress.media.runtime.AllowedTypesFor;
import org.quarkpress.persistence.NetworkOptions;

/** A single site reads none of the network's upload options, even with the network limits turned on. */
class SingleSiteNetworkOptionsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    SingleSiteNetworkOptionsDbTest.class,
                    TestStorage.class,
                    TestProcessor.class,
                    NetworkUploadFixtures.class)
            .overrideConfigKey("quarkpress.media.network-limits.enabled", "true");

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
    AllowedTypesFor allowedTypes;

    @Inject
    TestStorage storage;

    @Inject
    TestClock clock;

    NetworkUploadFixtures saved;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-05T10:00:00Z"));
        storage.clear();
        TestData.siteOption(dataSource, TestData.MAIN, "timezone_string", "\"UTC\"");
        saved = NetworkUploadFixtures.save(network);
    }

    @AfterEach
    void restoreTheNetwork() {
        saved.restore();
    }

    @Test
    void everyNetworkOptionIsIgnored() throws Exception {
        network.set("upload_space_check_disabled", JSON.textNode("0"));
        network.set("blog_upload_space", JSON.textNode("0"));
        network.set("fileupload_maxk", JSON.textNode("1"));
        network.set("upload_filetypes", JSON.textNode("jpg"));
        UserId carol = TestData.memberOfMain(dataSource, "author");
        Upload upload = Upload.builder("notes.txt", NetworkUploadFixtures.text("single-site-network-options", 200 * KB))
                .declaredType("text/plain")
                .build();
        assertThat(Identities.onSite(TestData.MAIN, Identities.user(carol), () -> media.store(upload))
                        .mimeType())
                .isEqualTo("text/plain");
        assertThat(Identities.onSite(
                        TestData.MAIN,
                        Identities.user(carol),
                        () -> allowedTypes.current().extensions()))
                .contains("txt", "pdf", "gif");
    }
}
