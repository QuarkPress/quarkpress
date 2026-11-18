package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every WordPress file {@code security-driver.php} loads is one each parity test that runs it downloads first. A test
 * that asks for fewer reads files only another test downloaded, so it fails whenever it runs first on a fresh checkout
 * (surefire's order follows the file system's). Reads the driver only: no PHP, no network.
 */
class SecurityDriverFilesTest {

    /** {@code QP_WP . 'name.php'} (wp-includes) and {@code ABSPATH . 'path.php'}. */
    static final Pattern LOADED = Pattern.compile("(QP_WP|ABSPATH)\\s*\\.\\s*'([^']+\\.php)'");

    static List<String> loadedByDriver() throws IOException {
        String driver = Files.readString(SecurityDriver.PATH, StandardCharsets.UTF_8);
        List<String> files = new ArrayList<>();
        Matcher m = LOADED.matcher(driver);
        while (m.find()) {
            files.add(m.group(1).equals("QP_WP") ? "wp-includes/" + m.group(2) : m.group(2));
        }
        return files;
    }

    @Test
    void theDriverLoadsFiles() throws IOException {
        assertThat(loadedByDriver()).contains("wp-includes/capabilities.php", "wp-admin/includes/schema.php");
    }

    @Test
    void theNamesParityTestDownloadsEveryFileTheDriverLoads() throws IOException {
        assertThat(UserNamesParityTest.WORDPRESS_FILES).containsAll(loadedByDriver());
    }

    @Test
    void theCapsParityTestDownloadsEveryFileTheDriverLoads() throws IOException {
        assertThat(MetaCapsParityTest.WORDPRESS_FILES).containsAll(loadedByDriver());
    }
}
