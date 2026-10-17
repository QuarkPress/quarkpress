package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;

class ApplicationObjectMapperDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(ApplicationObjectMapperDbTest.class)
            .overrideConfigKey("quarkus.jackson.write-dates-as-timestamps", "false");

    @Inject
    OptionRepository options;

    @Inject
    SiteBinding binding;

    @Inject
    TestSites sites;

    @Test
    void quarkPressJsonDoesNotDependOnTheApplicationsJackson() {
        SiteId site = sites.create("mapper");
        JsonNode value = Json.parse("{\"a\":[1,2]}");
        Requests.fresh(() -> {
            binding.bind(Binding.site(site));
            QuarkusTransaction.requiringNew()
                    .run(() -> options.persist(
                            OptionEntity.create(site, "mapper", value, true, Instant.parse("2026-10-17T12:00:00Z"))));
            QuarkusTransaction.requiringNew()
                    .run(() -> assertThat(options.findOption(site, "mapper").orElseThrow().value)
                            .isEqualTo(value));
        });
    }
}
