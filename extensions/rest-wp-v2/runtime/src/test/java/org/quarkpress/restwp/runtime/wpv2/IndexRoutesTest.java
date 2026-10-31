package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class IndexRoutesTest {

    @Test
    void authenticationNamesTheIssuerOnlyWhenTheDefaultTenantIsEnabled() {
        assertThat(IndexRoutes.authentication(true, Optional.of("https://id.example/realms/qp"))
                        .toString())
                .isEqualTo("{\"oidc\":{\"issuer\":\"https://id.example/realms/qp\"}}");
        assertThat(IndexRoutes.authentication(false, Optional.of("https://id.example/realms/qp"))
                        .toString())
                .isEqualTo("[]");
        assertThat(IndexRoutes.authentication(true, Optional.empty()).toString())
                .isEqualTo("[]");
        assertThat(IndexRoutes.authentication(true, Optional.of("")).toString()).isEqualTo("[]");
    }
}
