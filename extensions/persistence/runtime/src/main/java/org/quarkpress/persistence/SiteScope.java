package org.quarkpress.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.persistence.runtime.Scopes;

/**
 * Runs work for one site, for code without an HTTP request (jobs, startup, imports) or code that must touch another
 * site. Inside the work, {@code SiteContext} answers for that site and queries see only its rows.
 *
 * <ul>
 *   <li>When the caller is already bound to the same site, the work runs in the caller's request context and joins
 *       its transaction (or starts one if none is active).
 *   <li>Otherwise the work runs on a thread of its own (a new virtual thread, with no context propagated), in a fresh
 *       request context and a transaction of its own; the caller waits for it. It commits or rolls back independently
 *       of the caller's transaction, and it never changes the site that the caller, other threads sharing the
 *       caller's context, or tasks that captured it, see. Such a scope cannot be opened on a Vert.x event-loop
 *       thread.
 *   <li>Nested scopes for different sites each hold their own transaction, and so their own database connection,
 *       until they finish: deep nesting can exhaust the connection pool.
 *   <li>The work must finish before it returns: a returned {@code CompletionStage}, {@code Uni} or other
 *       asynchronous result is not awaited, and work it schedules runs after the scope (and its transaction) ended.
 * </ul>
 *
 * <p>What the work throws, exception or error, is rethrown unchanged; a failed rollback is added to it as suppressed.
 */
@ApplicationScoped
public class SiteScope {

    public void run(SiteId site, Runnable work) {
        Objects.requireNonNull(work, "work");
        try {
            Scopes.within(Binding.site(site), () -> {
                work.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public <T> T call(SiteId site, Callable<T> work) throws Exception {
        return Scopes.within(Binding.site(site), work);
    }
}
