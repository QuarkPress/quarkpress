package org.quarkpress.media.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import java.util.Optional;

/** Media settings fixed when the application is built. */
@ConfigMapping(prefix = "quarkpress.media")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface MediaBuildTimeConfig {

    /**
     * The storage backend to use when several are present: a backend module's name ({@code fs}, {@code s3}) or the
     * {@code @Identifier} (else the class simple name) of an application's {@code MediaStorage} bean. Needed only then.
     */
    Optional<String> storage();
}
