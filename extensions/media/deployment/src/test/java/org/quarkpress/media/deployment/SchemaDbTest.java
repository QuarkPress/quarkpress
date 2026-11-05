package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.media.runtime.MediaEntity;
import org.quarkpress.media.runtime.MediaRepository;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.NetworkScopePolicy;

class SchemaDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.with(SchemaDbTest.class, AllowThisTest.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> META_KEYS = List.of(
            "aperture",
            "credit",
            "camera",
            "caption",
            "created_timestamp",
            "copyright",
            "focal_length",
            "iso",
            "shutter_speed",
            "title",
            "orientation",
            "keywords",
            "alt");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    MediaRepository media;

    @Inject
    NetworkScope network;

    /** Lets this test open the network scope, where the site filter shows every site's rows. */
    @ApplicationScoped
    public static class AllowThisTest implements NetworkScopePolicy {
        @Override
        public boolean allows(Class<?> caller) {
            return caller == SchemaDbTest.class;
        }
    }

    @Test
    void theMigrationsOfBandSevenAreApplied() {
        assertThat(Jdbc.strings(
                        dataSource,
                        "select script from qp_schema_history where version like '7.%' order by installed_rank"))
                .containsExactly(
                        "db/quarkpress/" + TestDb.kind() + "/V7_001__media_create_table.sql",
                        "db/quarkpress/" + TestDb.kind() + "/V7_002__media_network_defaults.sql");
    }

    @Test
    void aNewNetworkHasWordPresssUploadOptions() {
        assertThat(networkOption("upload_space_check_disabled")).isEqualTo("\"1\"");
        assertThat(networkOption("fileupload_maxk")).isEqualTo("1500");
        assertThat(networkOption("blog_upload_space")).isEqualTo("100");
        assertThat(networkOption("upload_filetypes")).startsWith("\"jpg jpeg jpe gif png");
        assertThat(networkOption("upload_filetypes")).endsWith(" wp wpd key numbers pages\"");
    }

    @Test
    void deletingThePostOrTheSiteDeletesTheMediaRow() {
        SiteId site = TestData.site(dataSource);
        PostId kept = TestData.post(dataSource, site, "attachment", "inherit", "kept", "Kept");
        PostId gone = TestData.post(dataSource, site, "attachment", "inherit", "gone", "Gone");
        mediaRow(site, kept, "2026/11/kept.jpg", 10);
        mediaRow(site, gone, "2026/11/gone.jpg", 20);
        Jdbc.update(dataSource, "delete from qp_post where id = ?", gone.value());
        assertThat(Jdbc.strings(dataSource, "select storage_key from qp_media where site_id = ?", site.value()))
                .containsExactly("2026/11/kept.jpg");
        Jdbc.update(dataSource, "delete from qp_site where id = ?", site.value());
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_media where site_id = ?", site.value()))
                .isZero();
    }

    @Test
    void aMediaRowBelongsToAnAttachmentOfItsOwnSite() {
        SiteId one = TestData.site(dataSource);
        SiteId two = TestData.site(dataSource);
        PostId post = TestData.post(dataSource, one, "attachment", "inherit", "a", "A");
        assertThatThrownBy(() -> mediaRow(two, post, "sites/2/a.jpg", 1))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_media_post_fk"));
    }

    @Test
    void sizesAndMetaKeepTheirKeyOrder() throws Exception {
        SiteId site = TestData.site(dataSource);
        PostId post = TestData.post(dataSource, site, "attachment", "inherit", "photo", "Photo");
        ObjectNode sizes = JsonNodeFactory.instance.objectNode();
        for (String name : List.of("medium", "large", "thumbnail")) {
            sizes.putObject(name)
                    .put("file", "photo-" + name + ".jpg")
                    .put("width", 300)
                    .put("height", 200)
                    .put("mime-type", "image/jpeg")
                    .put("filesize", 1234);
        }
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        for (String key : META_KEYS) {
            if (key.equals("keywords")) {
                meta.putArray(key).add("b").add("a");
            } else {
                meta.put(key, key.equals("iso") ? "0" : "");
            }
        }
        ObjectNode extra = JsonNodeFactory.instance.objectNode().put("source_image", "x.png");
        extra.put("animated_video", "x.mp4");
        Identities.onSite(
                site,
                Identities.system(),
                () -> QuarkusTransaction.requiringNew().call(() -> {
                    MediaEntity row = MediaEntity.create(site, post);
                    row.storageKey = "sites/" + site.value() + "/2026/11/photo.jpg";
                    row.mimeType = "image/jpeg";
                    row.fileSize = 5000;
                    row.width = 640;
                    row.height = 480;
                    row.sizes = sizes;
                    row.imageMeta = meta;
                    row.extra = extra;
                    row.storedBytes = 8702;
                    media.persist(row);
                    return null;
                }));
        assertThat(keys(Jdbc.strings(dataSource, "select sizes from qp_media where post_id = ?", post.value())
                        .get(0)))
                .containsExactly("medium", "large", "thumbnail");
        assertThat(keys(Jdbc.strings(dataSource, "select image_meta from qp_media where post_id = ?", post.value())
                        .get(0)))
                .containsExactlyElementsOf(META_KEYS);
        MediaEntity read = Identities.onSite(site, Identities.system(), () -> media.findMedia(site, post))
                .orElseThrow();
        assertThat(keys(read.sizes)).containsExactly("medium", "large", "thumbnail");
        assertThat(keys(read.sizes.get("large"))).containsExactly("file", "width", "height", "mime-type", "filesize");
        assertThat(keys(read.imageMeta)).containsExactlyElementsOf(META_KEYS);
        assertThat(keys(read.extra)).containsExactly("source_image", "animated_video");
        assertThat(read.altText).isEmpty();
        assertThat(read.processing).isEqualTo("none");
        assertThat(read.originalKey).isNull();
        assertThat(read.width).isEqualTo(640);
        assertThat(read.version).isZero();
    }

    @Test
    void aNewRowHasEmptyAltTextSizesAndExtraAndNoImageMeta() throws Exception {
        SiteId site = TestData.site(dataSource);
        PostId post = TestData.post(dataSource, site, "attachment", "inherit", "doc", "Doc");
        Identities.onSite(
                site,
                Identities.system(),
                () -> QuarkusTransaction.requiringNew().call(() -> {
                    MediaEntity row = MediaEntity.create(site, post);
                    row.storageKey = "sites/" + site.value() + "/2026/11/doc.pdf";
                    row.mimeType = "application/pdf";
                    row.fileSize = 7;
                    row.storedBytes = 7;
                    media.persist(row);
                    return null;
                }));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(alt_text, '|', sizes, '|', extra, '|', coalesce(image_meta, 'null'), '|',"
                                + " coalesce(width, -1), '|', coalesce(height, -1)) from qp_media where post_id = ?",
                        post.value()))
                .containsExactly("|{}|{}|null|-1|-1");
    }

    @Test
    void aMediaRowOfAnotherSiteIsInvisible() throws Exception {
        SiteId one = TestData.site(dataSource);
        SiteId two = TestData.site(dataSource);
        PostId post = TestData.post(dataSource, one, "attachment", "inherit", "a", "A");
        mediaRow(one, post, "sites/1/a.jpg", 1);
        assertThat(Identities.onSite(one, Identities.system(), () -> media.findMedia(one, post)))
                .isPresent();
        assertThat(Identities.onSite(two, Identities.system(), () -> media.findMedia(two, post)))
                .isEmpty();
        // in the network scope the filter shows every site's rows: only the query's own site condition hides it
        assertThat(network.call(() -> media.findMedia(one, post))).isPresent();
        assertThat(network.call(() -> media.findMedia(two, post))).isEmpty();
        assertThat(network.call(() -> media.lockMedia(two, post))).isEmpty();
        assertThat(Identities.onSite(
                        two,
                        Identities.system(),
                        () -> QuarkusTransaction.requiringNew().call(() -> media.lockMedia(two, post))))
                .isEmpty();
        assertThat(Identities.onSite(
                        one,
                        Identities.system(),
                        () -> QuarkusTransaction.requiringNew()
                                .call(() -> media.lockMedia(one, post).map(row -> row.storageKey))))
                .contains("sites/1/a.jpg");
    }

    @Test
    void storedBytesSumsTheSitesRowsAndIsZeroWithoutAny() throws Exception {
        SiteId one = TestData.site(dataSource);
        SiteId two = TestData.site(dataSource);
        SiteId empty = TestData.site(dataSource);
        mediaRow(one, TestData.post(dataSource, one, "attachment", "inherit", "a", "A"), "a.jpg", 100);
        mediaRow(one, TestData.post(dataSource, one, "attachment", "inherit", "b", "B"), "b.jpg", 23);
        mediaRow(two, TestData.post(dataSource, two, "attachment", "inherit", "c", "C"), "c.jpg", 5000);
        assertThat(Identities.onSite(one, Identities.system(), () -> media.storedBytes(one)))
                .isEqualTo(123);
        assertThat(Identities.onSite(empty, Identities.system(), () -> media.storedBytes(empty)))
                .isZero();
        // in the network scope the filter shows every site's rows: only the query's own site condition limits the sum
        assertThat(network.call(() -> media.storedBytes(one))).isEqualTo(123);
        assertThat(network.call(() -> media.storedBytes(empty))).isZero();
    }

    @Test
    void theProcessingStateIsOneOfFive() {
        SiteId site = TestData.site(dataSource);
        PostId post = TestData.post(dataSource, site, "attachment", "inherit", "p", "P");
        assertThatThrownBy(() -> Jdbc.update(
                        dataSource,
                        "insert into qp_media (post_id, site_id, storage_key, mime_type, file_size, sizes, extra,"
                                + " alt_text, processing, stored_bytes) values (?, ?, 'p.jpg', 'image/jpeg', 1, '{}',"
                                + " '{}', '', 'unknown', 1)",
                        post.value(),
                        site.value()))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_media_processing_check"));
    }

    @Test
    void bothReferencesOfAMediaRowCascade() {
        String schema = TestDb.kind().equals("mysql") ? "database()" : "current_schema()";
        for (String constraint : List.of("qp_media_site_fk", "qp_media_post_fk")) {
            assertThat(Jdbc.strings(
                            dataSource,
                            "select delete_rule from information_schema.referential_constraints"
                                    + " where constraint_name = ? and constraint_schema = " + schema,
                            constraint))
                    .as(constraint)
                    .containsExactly("CASCADE");
        }
    }

    @Test
    void theNetworkDefaultsKeepOptionsAlreadySetAndAddMissingOnes() throws IOException {
        List<String> before =
                Jdbc.strings(dataSource, "select value from qp_network_option where name = 'upload_filetypes'");
        try {
            Jdbc.update(
                    dataSource, "update qp_network_option set value = '\"jpg png\"' where name = 'upload_filetypes'");
            Jdbc.update(dataSource, "delete from qp_network_option where name = 'fileupload_maxk'");
            for (String statement :
                    statements("db/quarkpress/" + TestDb.kind() + "/V7_002__media_network_defaults.sql")) {
                Jdbc.update(dataSource, statement);
            }
            assertThat(networkOption("upload_filetypes")).isEqualTo("\"jpg png\"");
            assertThat(networkOption("fileupload_maxk")).isEqualTo("1500");
            assertThat(networkOption("upload_space_check_disabled")).isEqualTo("\"1\"");
        } finally {
            Jdbc.update(
                    dataSource,
                    "update qp_network_option set value = " + TestDb.json() + " where name = 'upload_filetypes'",
                    before.get(0));
        }
    }

    /** The statements of a migration resource: its comment lines dropped, split at each semicolon. */
    private static List<String> statements(String resource) throws IOException {
        String sql;
        try (InputStream in = SchemaDbTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder text = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.startsWith("--")) {
                text.append(line).append('\n');
            }
        }
        List<String> statements = new ArrayList<>();
        for (String statement : text.toString().split(";")) {
            if (!statement.isBlank()) {
                statements.add(statement.strip());
            }
        }
        assertThat(statements).isNotEmpty();
        return statements;
    }

    private void mediaRow(SiteId site, PostId post, String key, long bytes) {
        Jdbc.update(
                dataSource,
                "insert into qp_media (post_id, site_id, storage_key, mime_type, file_size, sizes, extra, alt_text,"
                        + " processing, stored_bytes) values (?, ?, ?, 'image/jpeg', ?, " + TestDb.json() + ", "
                        + TestDb.json() + ", '', 'none', ?)",
                post.value(),
                site.value(),
                key,
                bytes,
                "{}",
                "{}",
                bytes);
    }

    private String networkOption(String name) {
        List<String> values = Jdbc.strings(dataSource, "select value from qp_network_option where name = ?", name);
        assertThat(values).as(name).hasSize(1);
        return values.get(0);
    }

    private static List<String> keys(String json) throws Exception {
        return keys(JSON.readTree(json));
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }
}
