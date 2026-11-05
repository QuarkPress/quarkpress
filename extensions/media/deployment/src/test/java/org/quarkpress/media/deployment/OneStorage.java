package org.quarkpress.media.deployment;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;

/** An in-memory storage backend named {@code one}, for the tests of the backend choice. */
@ApplicationScoped
@Identifier("one")
public class OneStorage extends MemoryStorage {}
