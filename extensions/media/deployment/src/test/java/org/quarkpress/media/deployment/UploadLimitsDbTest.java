package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.media.UploadCheck;
import org.quarkpress.media.UploadFilter;
import org.quarkpress.media.runtime.AllowedTypesFor;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * WordPress's network upload limits and upload types on a network: off as a QuarkPress network starts, WordPress's REST
 * order once on (space, then size, then quota) before the upload filters and the type check, the network's type list
 * narrowing every user's types, and HTML for super admins only.
 */
class UploadLimitsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    UploadLimitsDbTest.class,
                    TestStorage.class,
                    TestProcessor.class,
                    TestMultisiteMode.class,
                    NetworkUploadFixtures.class,
                    BlockingFilter.class)
            .overrideConfigKey("quarkpress.media.max-upload-size", "4M");

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    static final long KB = 1024, MB = 1_048_576;
    static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    static final String NOT_ALLOWED = "Sorry, you are not allowed to upload this file type.";

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
    SiteId site;
    UserId frank;
    UserId alice;

    /** Refuses uploads named {@code blocked.txt}. */
    @ApplicationScoped
    public static class BlockingFilter implements UploadFilter {
        @Override
        public Optional<String> refuse(UploadCheck upload) {
            return upload.clientName().equals("blocked.txt") ? Optional.of("Blocked by policy.") : Optional.empty();
        }
    }

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        saved = NetworkUploadFixtures.save(network);
        site = TestData.site(dataSource);
        TestData.siteOption(dataSource, site, "timezone_string", "\"UTC\"");
        frank = TestData.profile(dataSource, "frank");
        TestData.member(dataSource, site, frank, "[\"administrator\"]");
        alice = TestData.profile(dataSource, "alice");
        Jdbc.update(dataSource, "insert into qp_network_admin (user_id) values (?)", alice.value());
    }

    @AfterEach
    void restoreTheNetwork() {
        saved.restore();
    }

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "upload-limits"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Upload.Builder upload(String name, byte[] bytes, String declaredType) {
        return Upload.builder(name, file(bytes)).declaredType(declaredType);
    }

    private static Upload.Builder notes(String name, long size) {
        return Upload.builder(name, NetworkUploadFixtures.text("upload-limits", size))
                .declaredType("text/plain");
    }

    private MediaSnapshot as(UserId user, Upload.Builder upload) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> media.store(upload.build()));
    }

    private long attachments() {
        return Jdbc.count(
                dataSource, "select count(*) from qp_post where site_id = ? and type = 'attachment'", site.value());
    }

    /**
     * {@code upload} by {@code user} is refused with {@code code} and {@code message}; nothing is stored or written.
     */
    private void assertRefused(UserId user, Upload.Builder upload, String code, String message) {
        List<String> keys = storage.keys();
        int puts = storage.puts();
        long posts = attachments();
        long rows = Jdbc.count(dataSource, "select count(*) from qp_media where site_id = ?", site.value());
        assertThatThrownBy(() -> as(user, upload)).isInstanceOfSatisfying(MediaException.class, refusal -> {
            assertThat(refusal.code()).isEqualTo(code);
            assertThat(refusal).hasMessage(message);
        });
        assertThat(storage.puts()).as("files stored").isEqualTo(puts);
        assertThat(storage.keys()).isEqualTo(keys);
        assertThat(attachments()).isEqualTo(posts);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_media where site_id = ?", site.value()))
                .isEqualTo(rows);
    }

    private void networkText(String name, String value) {
        network.set(name, JSON.textNode(value));
    }

    @Test
    void aQuarkPressNetworkStartsWithTheChecksOff() throws Exception {
        assertThat(network.find("upload_space_check_disabled")).contains(JSON.textNode("1"));
        // 3 MB is over the per-file limit (1500 KB) and leaves the quota alone only because the checks are off
        networkText("blog_upload_space", "1");
        MediaSnapshot stored = as(frank, notes("big.txt", 3 * MB));
        assertThat(stored.fileSize()).isEqualTo(3 * MB);
        assertThat(stored.storageKey()).isEqualTo("sites/" + site.value() + "/2026/11/big.txt");
        // the option as a number reads the same
        network.set("upload_space_check_disabled", JSON.numberNode(1));
        assertThat(as(frank, notes("big2.txt", 3 * MB)).fileSize()).isEqualTo(3 * MB);
    }

    @Test
    void withTheChecksOnTheRestOrderApplies() throws Exception {
        networkText("upload_space_check_disabled", "0");
        networkText("blog_upload_space", "1");
        as(frank, notes("stored.txt", 900 * KB));
        assertRefused(
                frank,
                notes("more.txt", 200 * KB),
                "rest_upload_limited_space",
                "Not enough space to upload. 76 KB needed.");
        // the network check comes before the upload filters and the type check
        assertRefused(
                frank,
                notes("blocked.txt", 200 * KB),
                "rest_upload_limited_space",
                "Not enough space to upload. 76 KB needed.");
        byte[] exe = new byte[(int) (2 * MB)];
        exe[0] = 'M';
        exe[1] = 'Z';
        assertRefused(
                frank,
                upload("setup.exe", exe, "application/x-msdownload"),
                "rest_upload_limited_space",
                "Not enough space to upload. 1,924 KB needed.");
        // the configured maximum size comes first
        assertRefused(
                frank,
                notes("huge.txt", 5 * MB),
                "upload_error",
                "This file is too big. Files must be less than 4096 KB in size.");
        // the site's own quota overrides the network's
        TestData.siteOption(dataSource, site, "blog_upload_space", "\"2\"");
        assertThat(as(frank, notes("more.txt", 200 * KB)).fileSize()).isEqualTo(200 * KB);
        networkText("fileupload_maxk", "100");
        assertRefused(
                frank,
                notes("wide.txt", 150 * KB),
                "rest_upload_file_too_big",
                "This file is too big. Files must be less than 100 KB in size.");
        assertRefused(
                frank,
                upload("setup.exe", new byte[(int) (150 * KB)], "application/x-msdownload"),
                "rest_upload_file_too_big",
                "This file is too big. Files must be less than 100 KB in size.");
        assertThat(as(frank, notes("narrow.txt", 100 * KB)).fileSize()).isEqualTo(100 * KB);
        assertRefused(frank, notes("blocked.txt", 10 * KB), "upload_error", "Blocked by policy.");
        assertRefused(
                frank,
                upload("setup.exe", new byte[(int) (10 * KB)], "application/x-msdownload"),
                "upload_error",
                NOT_ALLOWED);
    }

    @Test
    void theOptionsMayBeStoredAsNumbers() throws Exception {
        network.set("upload_space_check_disabled", JSON.numberNode(0));
        network.set("blog_upload_space", JSON.numberNode(1));
        network.set("fileupload_maxk", JSON.numberNode(1500));
        as(frank, notes("stored.txt", 900 * KB));
        assertRefused(
                frank,
                notes("more.txt", 200 * KB),
                "rest_upload_limited_space",
                "Not enough space to upload. 76 KB needed.");
        TestData.siteOption(dataSource, site, "blog_upload_space", "2");
        assertThat(as(frank, notes("more.txt", 200 * KB)).fileSize()).isEqualTo(200 * KB);
        network.set("fileupload_maxk", JSON.numberNode(100));
        assertRefused(
                frank,
                notes("wide.txt", 150 * KB),
                "rest_upload_file_too_big",
                "This file is too big. Files must be less than 100 KB in size.");
        // a site quota that is not a number leaves the network's
        TestData.siteOption(dataSource, site, "blog_upload_space", "\"plenty\"");
        assertRefused(
                frank,
                notes("small.txt", 50 * KB),
                "rest_upload_limited_space",
                "Not enough space to upload. 50 KB needed.");
    }

    @Test
    void theEmptyFileMessageIsTheShortOne() throws Exception {
        assertRefused(
                frank,
                notes("empty.txt", 0),
                "upload_error",
                "File is empty. Please upload something more substantial.");
        // with the checks on, an empty file over the quota gets the quota message first
        networkText("upload_space_check_disabled", "0");
        networkText("blog_upload_space", "1");
        networkText("fileupload_maxk", "3000");
        TestData.siteOption(dataSource, site, "blog_upload_space", "\"2\"");
        as(frank, notes("fill.txt", 2 * MB - 1));
        TestData.siteOption(dataSource, site, "blog_upload_space", "\"1\"");
        assertRefused(
                frank,
                notes("empty.txt", 0),
                "rest_upload_user_quota_exceeded",
                "You have used your space quota. Please delete files before uploading.");
        assertRefused(
                frank, notes("one.txt", 1), "rest_upload_limited_space", "Not enough space to upload. 0 KB needed.");
    }

    @Test
    void aNegativeQuotaPutsAnEmptySiteOverIt() throws Exception {
        networkText("upload_space_check_disabled", "0");
        TestData.siteOption(dataSource, site, "blog_upload_space", "\"-1\"");
        assertRefused(
                frank,
                notes("empty.txt", 0),
                "rest_upload_user_quota_exceeded",
                "You have used your space quota. Please delete files before uploading.");
        assertRefused(
                frank, notes("one.txt", 2048), "rest_upload_limited_space", "Not enough space to upload. 2 KB needed.");
        // a zero quota with nothing stored: the empty file reaches the empty-file check
        TestData.siteOption(dataSource, site, "blog_upload_space", "0");
        assertRefused(
                frank,
                notes("empty.txt", 0),
                "upload_error",
                "File is empty. Please upload something more substantial.");
    }

    @Test
    void theNetworkListNarrowsTheTypes() throws Exception {
        networkText("upload_filetypes", "jpg png pdf");
        assertRefused(frank, notes("notes.txt", 10), "upload_error", NOT_ALLOWED);
        assertThat(as(frank, upload("photo.jpeg", MediaFixtures.jpeg(8, 8), "image/jpeg"))
                        .mimeType())
                .isEqualTo("image/jpeg");
        assertThat(extensions(frank)).containsExactlyInAnyOrder("jpg", "jpeg", "jpe", "png", "pdf");
        // a JPEG named with an extension the list leaves out is refused, not renamed to .jpg (as WordPress does)
        assertRefused(
                frank,
                upload("x.webp", MediaFixtures.jpeg(8, 8), "application/octet-stream"),
                "upload_error",
                NOT_ALLOWED);
        network.delete("upload_filetypes");
        assertThat(extensions(frank)).containsExactlyInAnyOrder("jpg", "jpeg", "jpe", "png", "gif");
        assertThat(extensions(alice)).containsExactlyInAnyOrder("jpg", "jpeg", "jpe", "png", "gif");
        assertRefused(frank, notes("notes.txt", 10), "upload_error", NOT_ALLOWED);
        assertThat(as(frank, upload("anim.gif", MediaFixtures.gif(4, 4, 1), "image/gif"))
                        .mimeType())
                .isEqualTo("image/gif");
        network.set("upload_filetypes", saved.saved("upload_filetypes").orElseThrow());
        assertThat(as(frank, notes("notes.txt", 10)).mimeType()).isEqualTo("text/plain");
    }

    @Test
    void siteAdministratorsLoseHtmlOnANetwork() throws Exception {
        byte[] page = MediaFixtures.text("<html><head><title>x</title></head><body><p>hi</p></body></html>\n");
        assertThat(extensions(frank)).doesNotContain("htm", "html", "js");
        assertThat(extensions(alice)).contains("htm", "html", "js");
        assertRefused(frank, upload("page.html", page, "text/html"), "upload_error", NOT_ALLOWED);
        assertThat(as(alice, upload("page.html", page, "text/html")).mimeType()).isEqualTo("text/html");
    }

    private Set<String> extensions(UserId user) throws Exception {
        return Identities.onSite(
                site, Identities.user(user), () -> allowedTypes.current().extensions());
    }
}
