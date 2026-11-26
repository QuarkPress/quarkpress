package org.quarkword.it.restwp;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import java.util.List;
import org.quarkword.security.ProfileClaims;
import org.quarkword.security.runtime.ProfileProvisioning;

/**
 * Gives the dev realm's five users their profiles at start, in WordPress's order of creation (alice, bob, carol, dave,
 * erin), so their ids never depend on which test logs a user in first: the parity run's id-ordered answers
 * ({@code users?orderby=id}) need the same order as WordPress's. Each profile is made as the initial super admin's is
 * (only the subject, named from the token's claims at its first login); one that exists is left as it is. Runs after
 * the security module's start-up bootstrap, which makes alice's.
 */
@ApplicationScoped
public class ItFixtureUsers {

    /** The {@code sub} of alice, bob, carol, dave and erin in the security module's dev realm, in that order. */
    static final List<String> SUBJECTS = List.of(
            "a11ce000-0000-4000-8000-000000000001",
            "b0b00000-0000-4000-8000-000000000002",
            "ca401000-0000-4000-8000-000000000003",
            "da7e0000-0000-4000-8000-000000000004",
            "e4100000-0000-4000-8000-000000000005");

    @Inject
    ProfileProvisioning provisioning;

    void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_AFTER + 10) StartupEvent start) {
        for (String subject : SUBJECTS) {
            provisioning.provisionBootstrap(ProfileClaims.subjectOnly(subject));
        }
    }
}
