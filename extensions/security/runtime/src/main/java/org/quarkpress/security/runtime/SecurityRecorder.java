package org.quarkpress.security.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.Recorder;
import java.util.function.Supplier;
import org.quarkpress.security.Roles;

@Recorder
public class SecurityRecorder {

    /** The default role table, recorded at build time in its stored JSON form. */
    public Supplier<Roles> roles(String json) {
        return () -> {
            try {
                return new RegisteredRoles(RoleTables.fromJson(new ObjectMapper().readTree(json)));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("the recorded role table is not JSON", e);
            }
        };
    }
}
