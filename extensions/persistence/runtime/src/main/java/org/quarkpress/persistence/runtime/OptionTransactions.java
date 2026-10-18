package org.quarkpress.persistence.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;
import org.quarkpress.core.NetworkOptionChanged;
import org.quarkpress.core.OptionChanged;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteFeatureToggled;

/**
 * Ties option writes to their transaction: events are fired to synchronous observers inside it (an observer failure
 * rolls it back), the request's caches are dropped again when it completes, and once it has committed the same events
 * are delivered to {@code @ObservesAsync} observers.
 */
@ApplicationScoped
public class OptionTransactions {

    private static final Logger LOG = Logger.getLogger(OptionTransactions.class);

    @Inject
    TransactionSynchronizationRegistry registry;

    /**
     * Fires an event; an observer that throws marks the transaction for rollback before the exception propagates, so
     * the write it was told about is never committed, whatever the exception type.
     */
    public void fire(Runnable fire) {
        try {
            fire.run();
        } catch (RuntimeException failure) {
            try {
                registry.setRollbackOnly();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    /**
     * Fires {@code payload} to synchronous observers now ({@link #fire}: a failure rolls the transaction back) and,
     * once the transaction has committed, to {@code @ObservesAsync} observers. Asynchronous observers therefore see
     * committed data and never hear of a change that was rolled back; their failures are logged.
     */
    public <T> void publish(Event<T> event, T payload) {
        fire(() -> event.fire(payload));
        registry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {}

            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) {
                    event.fireAsync(payload).exceptionally(failure -> {
                        LOG.warnf(failure, "an asynchronous observer of %s failed", describe(payload));
                        return payload;
                    });
                }
            }
        });
    }

    /** The event's type, site and option name, never its values: options may hold secrets. */
    private static String describe(Object payload) {
        StringBuilder text = new StringBuilder(payload.getClass().getSimpleName());
        if (payload instanceof SiteEvent siteEvent) {
            text.append(" of site ").append(siteEvent.site().value());
        }
        if (payload instanceof OptionChanged changed) {
            text.append(" for option '").append(changed.name()).append('\'');
        } else if (payload instanceof NetworkOptionChanged changed) {
            text.append(" for network option '").append(changed.name()).append('\'');
        } else if (payload instanceof SiteFeatureToggled toggled) {
            text.append(" for feature '").append(toggled.key()).append('\'');
        }
        return text.toString();
    }

    /**
     * Runs {@code forget} after the current transaction completes, whether it commits or rolls back: a thread sharing
     * the request may have cached the old value while the transaction was open, and after a rollback the value the
     * writer cached is gone.
     */
    public void afterCompletion(Runnable forget) {
        registry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {}

            @Override
            public void afterCompletion(int status) {
                forget.run();
            }
        });
    }
}
