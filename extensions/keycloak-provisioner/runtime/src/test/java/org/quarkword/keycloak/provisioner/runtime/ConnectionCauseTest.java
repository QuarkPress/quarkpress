package org.quarkword.keycloak.provisioner.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ProcessingException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/** A failure carries its cause only when that is a network error, never one that could quote Keycloak's answer. */
class ConnectionCauseTest {

    @Test
    void networkErrorsAreKept() {
        for (IOException network : new IOException[] {
            new ConnectException("Connection refused"),
            new UnknownHostException("keycloak"),
            new SocketTimeoutException("timed out"),
            new NoRouteToHostException("no route"),
            new SSLHandshakeException("bad certificate")
        }) {
            assertThat(KeycloakUserProvisioner.connectionCause(new ProcessingException(new RuntimeException(network))))
                    .as(network.getClass().getSimpleName())
                    .isSameAs(network);
        }
    }

    @Test
    void aParseErrorOfAnAnswerIsDropped() {
        JsonParseException parse = null;
        try {
            new ObjectMapper().readTree("<html>secret page</html>");
        } catch (JsonParseException e) {
            parse = e;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertThat(parse).isNotNull();
        assertThat(KeycloakUserProvisioner.connectionCause(new ProcessingException(parse)))
                .isNull();
        assertThat(KeycloakUserProvisioner.connectionCause(new ProcessingException(new IOException("body text"))))
                .isNull();
        assertThat(KeycloakUserProvisioner.connectionCause(new ProcessingException("no cause")))
                .isNull();
    }
}
