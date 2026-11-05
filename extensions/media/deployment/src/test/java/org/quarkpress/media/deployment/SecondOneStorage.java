package org.quarkpress.media.deployment;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;

/** A second in-memory storage backend named {@code one}, for the test of two beans with one name. */
@ApplicationScoped
@Identifier("one")
public class SecondOneStorage extends MemoryStorage {}
