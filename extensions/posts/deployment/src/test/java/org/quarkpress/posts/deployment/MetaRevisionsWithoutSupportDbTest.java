package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Singleton;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.MetaKeyProvider;
import org.quarkpress.posts.PostTypes;

class MetaRevisionsWithoutSupportDbTest {

    @Singleton
    static class RevisionedAttachmentKey implements MetaKeyProvider {
        @Override
        public List<MetaKeyDefinition> metaKeys(PostTypes types) {
            return List.of(MetaKeyDefinition.builder("memo_note")
                    .subtype("attachment")
                    .revisions()
                    .build());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(
                    MetaRevisionsWithoutSupportDbTest.class, RevisionedAttachmentKey.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("java.lang.IllegalStateException: QuarkPress: the meta key memo_note keeps revisions"
                            + " for attachment, which does not support revisions"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
