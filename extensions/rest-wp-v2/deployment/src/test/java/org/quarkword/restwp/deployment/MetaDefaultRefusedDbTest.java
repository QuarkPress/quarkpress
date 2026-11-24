package org.quarkword.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.posts.MetaKeyDefinition;
import org.quarkword.posts.MetaKeyProvider;
import org.quarkword.posts.PostTypes;

/**
 * A registered meta key whose default does not fit its schema (an integer key defaulting to {@code "x"}): WordPress
 * refuses the registration, QuarkWord refuses the start, naming the key.
 */
class MetaDefaultRefusedDbTest {

    @ApplicationScoped
    public static class BadDefault implements MetaKeyProvider {

        @Override
        public List<MetaKeyDefinition> metaKeys(PostTypes types) {
            return List.of(MetaKeyDefinition.builder("qw_bad_default")
                    .subtype("post")
                    .type("integer")
                    .defaultValue(TextNode.valueOf("x"))
                    .single()
                    .showInRest()
                    .build());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(MetaDefaultRefusedDbTest.class, BadDefault.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkWord: the default of meta key qw_bad_default does not match its schema"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
