package org.quarkpress.testing.wpscenario;

import io.agroal.api.AgroalDataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.posts.Posts;
import org.quarkpress.scheduling.runtime.Housekeeping;

/**
 * Plays the scheduling operations of a scenario on QuarkPress's main site and records what {@link SchedulingOutcomes}
 * records for WordPress: the publication check is the poller's per-post path as the system identity, the purges are
 * the housekeeping's purges of the main site, and the forced-date fixture is a JDBC write of the post's GMT date (the
 * local date derives from it; native SQL, so the statement names the site itself). Not thread-safe: one runner per
 * scenario run.
 */
@SiteScopedNativeSql
final class SchedulingScenarioRunner {

    static final SiteId MAIN = ScenarioParityBase.MAIN;

    private final Posts posts;
    private final Housekeeping housekeeping;
    private final AgroalDataSource dataSource;
    private final PostScenarioRunner postRunner;

    SchedulingScenarioRunner(
            Posts posts, Housekeeping housekeeping, AgroalDataSource dataSource, PostScenarioRunner postRunner) {
        this.posts = posts;
        this.housekeeping = housekeeping;
        this.dataSource = dataSource;
        this.postRunner = postRunner;
    }

    void run(int op, ScenarioOp.SchedulingOp step, Outcome outcome, List<UserId> users) throws Exception {
        String kind = step.kind();
        switch (step) {
            case ScenarioOp.RunPublishCheck check -> {
                Optional<PostId> post = postRunner.post(check.post());
                if (post.isEmpty()) {
                    outcome.done(op, kind, "missing");
                    return;
                }
                outcome.done(op, kind, publishCheck(post.get(), users));
            }
            case ScenarioOp.ForceDate force -> {
                Optional<PostId> post = postRunner.post(force.post());
                if (post.isEmpty()) {
                    outcome.done(op, kind, "missing");
                    return;
                }
                forceDate(post.get(), force.gmt());
                outcome.done(op, kind, "ok");
            }
            case ScenarioOp.RunTrashPurge purge -> {
                housekeeping.purgeTrash(MAIN);
                outcome.done(op, kind, SchedulingOutcomes.survivors(postRunner.survivors()));
            }
            case ScenarioOp.RunAutoDraftPurge purge -> {
                housekeeping.purgeAutoDrafts(MAIN);
                outcome.done(op, kind, SchedulingOutcomes.survivors(postRunner.survivors()));
            }
        }
    }

    /**
     * The poller's per-post path as the system: {@code ok} whether or not it published. A post the scenario deleted
     * meanwhile is {@code ok} without the call: WordPress's handler does nothing for a missing post, where QuarkPress
     * would refuse with {@code invalid_post}. Any refusal of the call itself is a difference and propagates.
     */
    private String publishCheck(PostId post, List<UserId> users) throws Exception {
        if (postRunner.as(0, users, () -> posts.find(post)).isEmpty()) {
            return "ok";
        }
        try {
            postRunner.as(0, users, () -> posts.publishIfDue(post));
            return "ok";
        } catch (ForbiddenException | UnauthorizedException refused) {
            return "refused:forbidden";
        }
    }

    /**
     * Test fixture, not WordPress behaviour: the row write the driver performs on WordPress's side, here of the GMT
     * date the local date derives from. No rule of an update applies, no modification stamp is set.
     */
    private void forceDate(PostId post, String gmt) throws Exception {
        Instant at = PostRecords.utc(gmt);
        String vendor = ConfigProvider.getConfig().getValue("quarkus.datasource.\"quarkpress\".db-kind", String.class);
        String sql;
        Object value;
        if (vendor.equals("postgresql")) {
            sql = "update qp_post set published_at = cast(? as timestamp with time zone) where site_id = ? and id = ?";
            value = at.toString();
        } else {
            // the datasource stores instants as UTC wall-clock texts
            sql = "update qp_post set published_at = cast(? as datetime(6)) where site_id = ? and id = ?";
            value = gmt;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            statement.setLong(2, MAIN.value());
            statement.setLong(3, post.value());
            int rows = statement.executeUpdate();
            if (rows != 1) {
                throw new IllegalStateException("force_date changed " + rows + " rows of post " + post.value());
            }
        }
    }
}
