package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.restwp.SettingDefinition;
import org.quarkpress.restwp.SettingsProvider;
import org.quarkpress.restwp.runtime.wpv2.SettingsController;

/**
 * A setting registered under a REST name one of WordPress's own settings already uses ({@code title}). The settings
 * route registers the settings while the application starts, so the refusal fails the start, with a message naming
 * the provider and the refused registration, instead of failing requests later.
 */
class SettingTwiceDbTest {

    @ApplicationScoped
    public static class TitleAgain implements SettingsProvider {

        @Override
        public List<SettingDefinition> settings() {
            return List.of(new SettingDefinition(
                    "title", "test_title", "string", Optional.empty(), JsonNodeFactory.instance.objectNode(), true));
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(SettingTwiceDbTest.class, TitleAgain.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress-rest-wp-v2: the REST routes of " + SettingsController.class.getName()
                            + " cannot be registered: the setting title is registered twice"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
