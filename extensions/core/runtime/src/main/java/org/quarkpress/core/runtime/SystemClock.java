package org.quarkpress.core.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import org.quarkpress.core.QuarkPressClock;

@DefaultBean
@ApplicationScoped
public class SystemClock implements QuarkPressClock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
