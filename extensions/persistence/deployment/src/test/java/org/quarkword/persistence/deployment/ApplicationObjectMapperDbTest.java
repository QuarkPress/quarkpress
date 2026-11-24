package org.quarkword.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteId;
import org.quarkword.core.runtime.Binding;
import org.quarkword.core.runtime.SiteBinding;
import org.quarkword.persistence.runtime.OptionEntity;
import org.quarkword.persistence.runtime.OptionRepository;

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
    void quarkWordJsonDoesNotDependOnTheApplicationsJackson() {
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
