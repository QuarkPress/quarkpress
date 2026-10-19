package org.quarkpress.security.runtime;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.PessimisticLockException;
import java.util.Optional;
import org.quarkpress.security.ProfileClaims;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.UserProfile;

/**
 * In dev mode, when {@code quarkpress.dev.seed-memberships} is on (the default), gives the dev realm's users their
 * profiles (named from the realm's claims) and their roles on the main site at startup; the network administrator of
 * the realm gets a network administrator row in a network, the administrator role on a single site. Records no login
 * (the profiles are made as {@link SuperAdminBootstrap} makes them). Runs as the system identity, without a request.
 * Idempotent and safe when several instances start together; never runs outside dev mode.
 */
@ApplicationScoped
public class DevMembershipSeeder {

    /** How many times a user's step is tried when it meets a concurrent change (another instance starting). */
    static final int ATTEMPTS = 3;

    @Inject
    DevConfig config;

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

    @Inject
    SystemIdentity system;

    void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_AFTER) StartupEvent start) {
        if (shouldSeed(LaunchMode.current(), config.seedMemberships())) {
            seed();
        }
    }

    /** Seeding happens in dev mode only, and only when the setting is on. */
    static boolean shouldSeed(LaunchMode mode, boolean enabled) {
        return mode == LaunchMode.DEVELOPMENT && enabled;
    }

    public void seed() {
        system.run(() -> {
            for (DevUsers.DevUser user : DevUsers.ALL) {
                for (int attempt = 1; ; attempt++) {
                    try {
                        ensure(user);
                        break;
                    } catch (PessimisticLockException conflict) {
                        if (attempt == ATTEMPTS) {
                            throw conflict;
                        }
                    }
                }
            }
        });
    }

    private void ensure(DevUsers.DevUser user) {
        UserProfile profile = provisioning.provision(
                new ProfileClaims(
                        user.id(),
                        Optional.of(user.username()),
                        Optional.of(user.givenName()),
                        Optional.of(user.familyName())),
                false);
        if (user.networkAdmin() && mode.multisite()) {
            admins.grant(profile.id(), Optional.empty());
        } else {
            memberships.setRoleWithoutCheck(checkSite.main(), profile.id(), user.role());
        }
    }
}
