package org.quarkpress.media.deployment;

import jakarta.enterprise.context.ApplicationScoped;

/** The in-memory storage bean of most test applications ({@link MediaApp#with}); it is named by its class. */
@ApplicationScoped
public class MemoryStorageBean extends MemoryStorage {}
