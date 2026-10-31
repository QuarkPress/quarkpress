package org.quarkpress.security.runtime;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.PessimisticLockException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.security.ProfileClaims;
import org.quarkpress.security.UserProfile;

/**
 * At every start, gives each subject of {@code quarkpress.security.initial-super-admins} a profile if it has none and,
 * in a network, a network administrator row, or, on a single site, the administrator role on the main site. Subjects
 * are trimmed and empty ones skipped. Records no login: an existing profile's login time is left as it is, and a
 * profile created here has none. The profile is created as {@link DefaultUserProfileProvisioner} names one, even when
 * an application replaces the provisioner, and marked so that its first login names it from the token's claims. Idempotent: a subject that already has what it would get is left as it
 * is. Runs without a capability check (no identity is needed) and after the checks of the main site and the network
 * made at start.
 */
@ApplicationScoped
public class SuperAdminBootstrap {

    /** How many times a subject's step is tried when it meets a concurrent change (another instance starting). */
    static final int ATTEMPTS = 3;

    @Inject
    SecurityConfig config;

    @Inject
    InstallMode mode;

    @Inject
    ProfileProvisioning provisioning;

    @Inject
    NetworkAdmins admins;

    @Inject
    JpaMemberships memberships;

    @Inject
    CheckSite checkSite;

    void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_AFTER) StartupEvent start) {
        apply();
    }

    public void apply() {
        for (String subject : subjects()) {
            ProfileClaims claims = ProfileClaims.subjectOnly(subject);
            for (int attempt = 1; ; attempt++) {
                try {
                    ensure(provisioning.provisionBootstrap(claims));
                    break;
                } catch (PessimisticLockException conflict) {
                    if (attempt == ATTEMPTS) {
                        throw conflict;
                    }
                }
            }
        }
    }

    /**
     * The configured subjects, trimmed, without empty ones and duplicates, in order. SmallRye Config does not trim the
     * elements of a list ({@code a, b} gives {@code " b"}), and a padded {@code sub} could never log in.
     */
    private Set<String> subjects() {
        Set<String> subjects = new LinkedHashSet<>();
        for (String subject : config.initialSuperAdmins().orElse(List.of())) {
            String trimmed = subject.strip();
            if (!trimmed.isEmpty()) {
                subjects.add(trimmed);
            }
        }
        return subjects;
    }

    private void ensure(UserProfile profile) {
        if (mode.multisite()) {
            admins.grant(profile.id(), Optional.empty());
        } else {
            memberships.addRoleWithoutCheck(checkSite.main(), profile.id(), BuiltInRoles.ADMINISTRATOR);
        }
    }
}
