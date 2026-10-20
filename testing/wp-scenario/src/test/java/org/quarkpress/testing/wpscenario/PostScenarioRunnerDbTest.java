package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.Arc;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.core.UserId;

/** The post runner on QuarkPress alone: the identity it acts as never outlives an operation. */
@QuarkusTest
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class PostScenarioRunnerDbTest extends ScenarioParityBase {

    /** Post operations that insert nothing (a refused insert, a query as a user), then a capability check. */
    static final String NOTHING_INSERTED = """
            {"id": "runner-nothing-inserted", "rule": "POST1", "variant": "subdirectory", "ops": [
              {"op": "create_user", "username": "ed", "given_name": "", "family_name": ""},
              {"op": "add_user", "user": 1, "site": 0, "role": "editor"},
              {"op": "create_user", "username": "au", "given_name": "", "family_name": ""},
              {"op": "add_user", "user": 2, "site": 0, "role": "author"},
              {"op": "insert_post", "as": 2, "fields": {"type": "page", "title": "Not mine", "status": "publish"}},
              {"op": "query_posts", "as": 2, "args": {}},
              {"op": "can", "user": 1, "site": 0, "capability": "edit_pages", "target": 0}
            ]}
            """;

    @Test
    void aScenarioThatInsertsNoPostCleansUpAndCanRunAgain() throws Exception {
        Scenario scenario = ScenarioJson.parse(NOTHING_INSERTED);
        for (int run = 0; run < 2; run++) {
            Outcome outcome = runOnQuarkPress(scenario);
            assertThat(outcome.facts())
                    .containsEntry("op5:insert_post", "refused:forbidden")
                    .containsEntry("op6:query_posts", "(none) found=0")
                    .containsEntry("op7:can", "yes");
        }
    }

    @Test
    void theCallersIdentityIsRestoredAfterAnOperationAsAUser() throws Exception {
        PostScenarioRunner runner = new PostScenarioRunner(posts, scope, store, options, false);
        CurrentIdentityAssociation association =
                Arc.container().instance(CurrentIdentityAssociation.class).get();
        ScenarioOp.QueryPosts query =
                new ScenarioOp.QueryPosts(1, QueryArgs.builder().build());
        // bound to the main site already, so the runner's work joins this request context
        SecurityIdentity[] seen = scope.call(MAIN, () -> {
            SecurityIdentity before = association.getIdentity();
            runner.run(1, query, new Outcome(), List.of(UserId.of(Long.MAX_VALUE)));
            return new SecurityIdentity[] {before, association.getIdentity()};
        });
        assertThat(seen[1]).isSameAs(seen[0]);
    }
}
