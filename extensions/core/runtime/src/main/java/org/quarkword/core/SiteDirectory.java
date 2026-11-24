package org.quarkword.core;

import java.util.Optional;

/** Looks sites up by id. The persistence module provides the real directory. */
public interface SiteDirectory {

    Optional<SiteInfo> find(SiteId site);
}
