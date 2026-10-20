package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.posts.runtime.PostsConfig;

class PostsConfigDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(PostsConfigDbTest.class);

    @Inject
    PostsConfig config;

    @Test
    void trashIsKeptThirtyDaysByDefault() {
        assertThat(config.emptyTrashDays()).isEqualTo(30);
    }
}
