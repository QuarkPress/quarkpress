package org.quarkpress.keycloak.provisioner.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.jboss.logging.Logger;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.quarkpress.security.NewUser;
import org.quarkpress.security.ProvisionedIdentity;
import org.quarkpress.security.UserProvisioner;
import org.quarkpress.security.UserProvisioningException;
import org.quarkpress.security.UserProvisioningException.Reason;

/**
 * Creates users in Keycloak through its Admin REST API (Quarkus's admin client, {@code quarkus.keycloak.admin-client.*})
 * in the realm {@code quarkpress.keycloak-provisioner.realm}. A WordPress hash is stored as a {@code wordpress} password
 * credential, which the quarkpress-keycloak hash provider verifies at sign-in; Keycloak then re-hashes the password
 * with the realm's algorithm.
 *
 * <p>An existing user of the username is returned unchanged with its credentials, as WordPress's importers map an
 * existing login to its user. Failures carry fixed texts: neither the request, the user's fields, the hash nor
 * Keycloak's answer is put in a message or a log record. There is no retry; the call is idempotent, so the caller
 * retries.
 */
@ApplicationScoped
public class KeycloakUserProvisioner implements UserProvisioner {

    private static final Logger LOG = Logger.getLogger(KeycloakUserProvisioner.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The fields of a user Keycloak may name in a refusal, as Keycloak names them. */
    private static final Set<String> FIELDS = Set.of("username", "email", "firstName", "lastName");

    /** Keycloak's message keys for refused user fields that a refusal may name. */
    private static final Set<String> ERRORS = Set.of(
            "error-username-invalid-character",
            "error-invalid-length",
            "error-invalid-email",
            "error-invalid-value",
            "error-invalid-blank",
            "error-person-name-invalid-character",
            "error-user-attribute-required",
            "error-username-invalid-idn-homograph",
            "error-user-attribute-read-only");

    @Inject
    Keycloak keycloak;

    @Inject
    KeycloakProvisionerConfig config;

    /** Runs in a request context of its own when called outside one: the admin client is request-scoped. */
    @Override
    @ActivateRequestContext
    public ProvisionedIdentity provision(NewUser user) {
        Objects.requireNonNull(user, "user");
        String realm = config.realm()
                .filter(name -> !name.isBlank())
                .orElseThrow(() -> new UserProvisioningException(
                        Reason.NOT_CONFIGURED,
                        "quarkpress.keycloak-provisioner.realm is not set: the Keycloak realm users are created in."));
        authenticate();
        UsersResource users = keycloak.realm(realm).users();
        try (Response response = users.create(representation(user))) {
            int status = response.getStatus();
            LOG.debugf("Keycloak answered %d to a user creation", status);
            if (status == 201) {
                return new ProvisionedIdentity(createdId(response, users, user.username()), true);
            }
            if (status == 409) {
                return existing(users, user.username());
            }
            throw refusal(status, response);
        } catch (ProcessingException | WebApplicationException e) {
            throw failure(e);
        }
    }

    /** Obtains the admin client's token first, so that a refusal of its credentials is told apart from the user's. */
    private void authenticate() {
        try {
            keycloak.tokenManager().getAccessTokenString();
        } catch (ProcessingException | WebApplicationException e) {
            Optional<Integer> status = httpStatus(e);
            if (status.isPresent() && status.get() < 500) {
                LOG.debugf("Keycloak answered %d to the admin client's token request", status.get());
                throw new UserProvisioningException(
                        Reason.UNAUTHORIZED,
                        "Keycloak refused the admin client's credentials (quarkus.keycloak.admin-client.*).");
            }
            throw failure(e);
        }
    }

    private UserRepresentation representation(NewUser user) {
        UserRepresentation representation = new UserRepresentation();
        representation.setUsername(user.username());
        user.email().ifPresent(email -> {
            representation.setEmail(email);
            representation.setEmailVerified(config.emailVerified());
        });
        user.firstName().ifPresent(representation::setFirstName);
        user.lastName().ifPresent(representation::setLastName);
        representation.setEnabled(true);
        representation.setRequiredActions(List.of());
        user.password()
                .ifPresent(hash -> representation.setCredentials(List.of(CredentialRepresentations.wordpress(hash))));
        return representation;
    }

    /** The id of the created user: the last segment of the answer's {@code Location}, else found by username. */
    private static String createdId(Response response, UsersResource users, String username) {
        URI location = response.getLocation();
        if (location != null && location.getPath() != null) {
            String path = location.getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            if (!id.isBlank()) {
                return id;
            }
        }
        return find(users, username)
                .orElseThrow(() -> new UserProvisioningException(
                        Reason.UNAVAILABLE, "Keycloak created the user but does not find it by its username."));
    }

    /**
     * After a conflict: the user of that username, unchanged. Keycloak's exact search ignores case, as Keycloak
     * stores usernames lower-cased. None means the e-mail address was the conflict.
     */
    private static ProvisionedIdentity existing(UsersResource users, String username) {
        return find(users, username)
                .map(id -> new ProvisionedIdentity(id, false))
                .orElseThrow(() -> new UserProvisioningException(
                        Reason.EMAIL_TAKEN, "Another Keycloak user has the user's e-mail address."));
    }

    private static Optional<String> find(UsersResource users, String username) {
        return matching(users.searchByUsername(username, true), username);
    }

    /** The id of the user in {@code found} whose username is {@code username}, ignoring case as Keycloak does. */
    static Optional<String> matching(List<UserRepresentation> found, String username) {
        for (UserRepresentation candidate : found) {
            if (username.equalsIgnoreCase(candidate.getUsername())) {
                return Optional.of(candidate.getId());
            }
        }
        return Optional.empty();
    }

    private static UserProvisioningException refusal(int status, Response response) {
        if (status == 400) {
            return invalid(response);
        }
        if (status == 404) {
            return new UserProvisioningException(
                    Reason.NOT_CONFIGURED,
                    "Keycloak has no realm of the name quarkpress.keycloak-provisioner.realm sets.");
        }
        if (status == 401 || status == 403) {
            return new UserProvisioningException(
                    Reason.UNAUTHORIZED,
                    "Keycloak refused to create the user with the admin client's credentials: the client needs the"
                            + " realm-management roles manage-users and view-users.");
        }
        return new UserProvisioningException(
                Reason.UNAVAILABLE, "Keycloak could not create the user: it answered HTTP " + status + ".");
    }

    /** Keycloak's refusal of the user's fields. */
    private static UserProvisioningException invalid(Response response) {
        String body = null;
        try {
            body = response.readEntity(String.class);
        } catch (ProcessingException | IllegalStateException e) {
            LOG.debug("Keycloak's refusal of a user could not be read");
        }
        return new UserProvisioningException(Reason.INVALID_USER, invalidUserMessage(body));
    }

    /**
     * The message for Keycloak's refusal {@code body} of a user's fields. It names the field and Keycloak's message key
     * only when they are known ones, so nothing else of Keycloak's answer is carried; an unreadable body gives the
     * generic text.
     */
    static String invalidUserMessage(String body) {
        String field = null;
        String error = null;
        if (body != null) {
            try {
                JsonNode json = JSON.readTree(body);
                if (json != null) {
                    field = known(FIELDS, json.path("field").asText(null));
                    error = known(ERRORS, json.path("errorMessage").asText(null));
                }
            } catch (IOException e) {
                // not JSON: the generic text
            }
        }
        StringBuilder message = new StringBuilder("Keycloak refused the user");
        if (field != null) {
            message.append("'s ").append(field);
        }
        if (error != null) {
            message.append(": ").append(error);
        }
        return message.append('.').toString();
    }

    /** The element of {@code values} equal to {@code value}, so that only our own text is used; else null. */
    private static String known(Set<String> values, String value) {
        if (value == null) {
            return null;
        }
        for (String known : values) {
            if (known.equals(value)) {
                return known;
            }
        }
        return null;
    }

    /** A failure of the call itself: an HTTP answer by its status, anything else as unreachable. */
    private static UserProvisioningException failure(RuntimeException e) {
        Optional<Integer> status = httpStatus(e);
        if (status.isPresent()) {
            int code = status.get();
            if (code == 401 || code == 403) {
                return new UserProvisioningException(
                        Reason.UNAUTHORIZED,
                        "Keycloak refused the admin client's credentials (quarkus.keycloak.admin-client.*).");
            }
            return new UserProvisioningException(
                    Reason.UNAVAILABLE, "Keycloak could not create the user: it answered HTTP " + code + ".");
        }
        return new UserProvisioningException(Reason.UNAVAILABLE, "Keycloak cannot be reached.", connectionCause(e));
    }

    /** The HTTP status of the first {@link WebApplicationException} in the cause chain. */
    private static Optional<Integer> httpStatus(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof WebApplicationException web && web.getResponse() != null) {
                return Optional.of(web.getResponse().getStatus());
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    /**
     * The network error behind a failure (refused or timed-out connection, unknown host, no route, TLS failure),
     * without the REST client's wrappers: it names the address, never the request. Null for any other failure, whose
     * message could quote Keycloak's answer (a parse error of an answer quotes it).
     */
    static Throwable connectionCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof SocketTimeoutException
                    || current instanceof NoRouteToHostException
                    || current instanceof SSLException) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }
}
