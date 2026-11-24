package org.quarkword.media.image.vips.runtime;

import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilder;

/** Adds {@link VipsLimitDefaults} to the application's run-time configuration. */
public final class VipsConfigBuilder implements ConfigBuilder {

    @Override
    public SmallRyeConfigBuilder configBuilder(SmallRyeConfigBuilder builder) {
        return builder.withInterceptors(new VipsLimitDefaults());
    }
}
