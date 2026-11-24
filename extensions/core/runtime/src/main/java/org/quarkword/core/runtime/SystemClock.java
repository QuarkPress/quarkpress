package org.quarkword.core.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import org.quarkword.core.QuarkWordClock;

@DefaultBean
@ApplicationScoped
public class SystemClock implements QuarkWordClock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
