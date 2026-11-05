package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The network's upload type list, as the migration writes it, is the one the Java upload type list gives. */
class NetworkDefaultsMigrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "mysql"})
    void theNetworkUploadListIsEveryExtensionOfTheInstallersMap(String kind) throws IOException {
        String sql = resource("db/quarkpress/" + kind + "/V7_002__media_network_defaults.sql");
        assertThat(sql).contains("'\"" + String.join(" ", DefaultMimeTypes.installerExtensions()) + "\"'");
    }

    private static String resource(String name) throws IOException {
        try (InputStream in =
                NetworkDefaultsMigrationTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
