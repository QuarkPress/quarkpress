package org.quarkpress.it.posts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.it.posts.SchedulingCalls.await;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.RestAssured;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Two nodes of the same application on one database: the packaged application is launched a second time on the
 * Dev Services database and Keycloak, both poll every second, and every due post is published exactly once in all.
 * The test does not ask that each node publishes some of the posts: both nodes select the due posts in the same
 * order and neither skips locked rows, so a node whose poll runs while the other holds the locks waits for each row
 * and then finds it published; that node may answer "already published" for every post of the run. What the design
 * promises is that any number of nodes may poll and that exactly one of them publishes each post, so the test
 * asserts that, and that both nodes polled after the posts became due: the second node saw them and took none twice.
 */
@QuarkusIntegrationTest
class MultiNodeIT implements DevServicesContext.ContextAware {

    static final int POSTS = 20;

    private DevServicesContext context;

    SchedulingCalls calls = new SchedulingCalls();

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        this.context = context;
        calls.setIntegrationTestContext(context);
    }

    @Test
    void twoNodesPublishEveryDuePostExactlyOnce() throws Exception {
        try (SecondNode second = SecondNode.start(context.devServicesProperties())) {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < POSTS; i++) {
                ids.add(calls.scheduleFarAhead());
            }
            assertThat(calls.statusOn(second.port(), ids.get(0)))
                    .as("the second node reads a post the first node created")
                    .isEqualTo("future");
            for (long id : ids) {
                calls.due(id);
            }
            Instant dueAt = Instant.now();
            await(
                    Duration.ofSeconds(90),
                    () -> publishedOf(ids, RestAssured.port).size()
                                    + publishedOf(ids, second.port()).size()
                            >= POSTS,
                    "the two nodes to publish " + POSTS + " posts");
            await(Duration.ofSeconds(30), () -> polledAfter(RestAssured.port, dueAt), "the first node to poll again");
            await(Duration.ofSeconds(30), () -> polledAfter(second.port(), dueAt), "the second node to poll again");
            List<Long> first = publishedOf(ids, RestAssured.port);
            List<Long> other = publishedOf(ids, second.port());
            List<Long> all = new ArrayList<>(first);
            all.addAll(other);
            assertThat(first.size() + other.size()).isEqualTo(POSTS);
            assertThat(all).hasSize(POSTS).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
            for (long id : ids) {
                assertThat(calls.status(id)).isEqualTo("publish");
            }
        }
    }

    /** The posts among {@code ids} that the node on {@code port} published. */
    private List<Long> publishedOf(List<Long> ids, int port) {
        return calls.publishedOn(port).stream().filter(ids::contains).toList();
    }

    /** Whether the node on {@code port} has ended a poll after {@code instant}. */
    private boolean polledAfter(int port, Instant instant) {
        return calls.lastPollEndedOn(port)
                .filter(ended -> ended.isAfter(instant))
                .isPresent();
    }
}
