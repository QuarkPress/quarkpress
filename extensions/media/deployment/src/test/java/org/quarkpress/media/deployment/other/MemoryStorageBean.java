package org.quarkpress.media.deployment.other;

import jakarta.enterprise.context.ApplicationScoped;
import org.quarkpress.media.deployment.MemoryStorage;

/** An in-memory storage bean with the same simple class name as the usual test bean, in another package. */
@ApplicationScoped
public class MemoryStorageBean extends MemoryStorage {}
