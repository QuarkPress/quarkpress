package org.quarkpress.core;

import java.time.Instant;

/**
 * An event about one site. Events are fired synchronously inside the transaction that caused them; an observer that
 * throws rolls the change back. Observers that need committed data use
 * {@code @Observes(during = TransactionPhase.AFTER_SUCCESS)}; {@code @ObservesAsync} is fire-and-forget.
 */
public interface SiteEvent {

    SiteId site();

    Instant at();
}
