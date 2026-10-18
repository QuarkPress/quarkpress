package org.quarkpress.core;

import java.time.Instant;

/**
 * An event about one site. QuarkPress fires events synchronously inside the transaction that caused them (an observer
 * that throws rolls the change back) and, once that transaction commits, again to {@code @ObservesAsync} observers.
 * Observers that need committed data use {@code @Observes(during = TransactionPhase.AFTER_SUCCESS)} or
 * {@code @ObservesAsync}. The asynchronous deliveries of the events of one transaction are independent: their order
 * is not guaranteed. A {@code @SiteFeature} gate on an observer of a site event checks the event's site.
 */
public interface SiteEvent {

    SiteId site();

    Instant at();
}
