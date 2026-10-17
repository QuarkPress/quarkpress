package org.quarkpress.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CoreExtensionTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest().withEmptyApplication();

    @Test
    void describesItselfAsAQuarkusExtension() throws IOException {
        List<URL> descriptors = Collections.list(
                Thread.currentThread().getContextClassLoader().getResources("META-INF/quarkus-extension.properties"));
        Properties core = null;
        for (URL url : descriptors) {
            Properties candidate = new Properties();
            try (InputStream in = url.openStream()) {
                candidate.load(in);
            }
            if (candidate.getProperty("deployment-artifact", "").contains("quarkpress-core-deployment")) {
                core = candidate;
            }
        }
        assertThat(core).as("quarkus-extension.properties of quarkpress-core").isNotNull();
        assertThat(core.getProperty("provides-capabilities")).contains("org.quarkpress.core");
    }
}
