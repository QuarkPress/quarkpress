package org.quarkpress.it.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.ImportedUser;
import org.quarkpress.security.NewUser;
import org.quarkpress.security.PasswordHash;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.UserImports;
import org.quarkpress.security.UserProvisioningException;

/**
 * Test scaffolding, not part of any API: imports users through {@link UserImports} as the system identity (as an
 * importer runs), and reads a Keycloak user's credentials through the provisioner's admin client so that the tests can
 * see which algorithm stores a password. Every call needs a caller holding {@code create_users} (alice, the main site's
 * administrator); a credential's secret data (its hash and salt) is never read or answered.
 */
@Path("/it")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ItKeycloakResource {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A user to import; every field but {@code username} and {@code displayName} may be null. */
    public record ImportRequest(
            String username, String email, String displayName, String firstName, String lastName, String hash) {}

    /** The outcome of an import: the Keycloak subject and the QuarkPress profile. */
    public record ImportResponse(
            String subject,
            long userId,
            String nicename,
            String displayName,
            boolean createdInIdentityProvider,
            boolean profileCreated) {}

    /** A refused import: the provisioner's reason and fixed message. */
    public record ImportFailure(String reason, String message) {}

    /** A user Keycloak creates with a password it hashes itself, for comparison with a migrated one. */
    public record ReferenceRequest(String username, String password) {}

    /** A Keycloak credential without its secret data: id, type, algorithm and the credential data as stored. */
    public record Credential(String id, String type, String algorithm, String credentialData) {}

    @Inject
    UserImports imports;

    @Inject
    SystemIdentity system;

    @Inject
    Capabilities capabilities;

    @Inject
    Keycloak keycloak;

    @ConfigProperty(name = "quarkpress.keycloak-provisioner.realm")
    String realm;

    @POST
    @Path("/import")
    public Response importUser(ImportRequest request) {
        requireCreateUsers();
        NewUser user = new NewUser(
                request.username(),
                Optional.ofNullable(request.email()),
                request.displayName(),
                Optional.ofNullable(request.firstName()),
                Optional.ofNullable(request.lastName()),
                Optional.ofNullable(request.hash()).map(PasswordHash::wordpress));
        try {
            ImportedUser imported = asSystem(() -> imports.importUser(user));
            return Response.ok(new ImportResponse(
                            imported.profile().subject(),
                            imported.profile().id().value(),
                            imported.profile().nicename(),
                            imported.profile().displayName(),
                            imported.createdInIdentityProvider(),
                            imported.profileCreated()))
                    .build();
        } catch (UserProvisioningException e) {
            int status = switch (e.reason()) {
                case EMAIL_TAKEN, INVALID_USER -> 409;
                case NOT_CONFIGURED, UNAUTHORIZED, UNAVAILABLE -> 502;
            };
            return Response.status(status)
                    .entity(new ImportFailure(e.reason().name(), e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/credentials/{subject}")
    public List<Credential> credentials(@PathParam("subject") String subject) {
        requireCreateUsers();
        List<Credential> credentials = new ArrayList<>();
        for (CredentialRepresentation credential :
                keycloak.realm(realm).users().get(subject).credentials()) {
            credentials.add(new Credential(
                    credential.getId(),
                    credential.getType(),
                    algorithm(credential.getCredentialData()),
                    credential.getCredentialData()));
        }
        return credentials;
    }

    @POST
    @Path("/reference-users")
    public String referenceUser(ReferenceRequest request) {
        requireCreateUsers();
        CredentialRepresentation password = new CredentialRepresentation();
        password.setType(CredentialRepresentation.PASSWORD);
        password.setValue(request.password());
        password.setTemporary(false);
        UserRepresentation user = new UserRepresentation();
        user.setUsername(request.username());
        user.setEnabled(true);
        user.setCredentials(List.of(password));
        try (Response created = keycloak.realm(realm).users().create(user)) {
            if (created.getStatus() != 201) {
                throw new IllegalStateException("Keycloak answered " + created.getStatus() + " to a reference user");
            }
            String path = created.getLocation().getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    private void requireCreateUsers() {
        if (!capabilities.can("create_users")) {
            throw new ForbiddenException();
        }
    }

    private static String algorithm(String credentialData) {
        if (credentialData == null) {
            return null;
        }
        try {
            JsonNode data = JSON.readTree(credentialData);
            return data.path("algorithm").asText(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs {@code work} as the system identity, on a thread of its own: the system never acts inside a request. */
    private <T> T asSystem(Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(() -> system.call(work));
        Thread.ofVirtual().start(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
