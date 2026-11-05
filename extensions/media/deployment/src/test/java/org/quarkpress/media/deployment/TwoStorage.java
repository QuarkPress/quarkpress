package org.quarkpress.media.deployment;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;

/** An in-memory storage backend named {@code two}, for the tests of the backend choice. */
@ApplicationScoped
@Identifier("two")
public class TwoStorage extends MemoryStorage {}
