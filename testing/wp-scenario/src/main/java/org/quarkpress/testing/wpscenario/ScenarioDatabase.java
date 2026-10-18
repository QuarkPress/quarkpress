package org.quarkpress.testing.wpscenario;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The MySQL server WordPress is installed into: a Testcontainers container with a pinned image, reached as root (the
 * PHP driver creates one database per installation and per scenario). Locally the container runs on Podman through
 * the {@code DOCKER_HOST} the Maven wrapper sets.
 */
public final class ScenarioDatabase implements AutoCloseable {

    /** The pinned server image. */
    public static final String IMAGE = "mysql:9.5";

    static final String PASSWORD = "scenario";

    private final MySQLContainer container;

    private ScenarioDatabase(MySQLContainer container) {
        this.container = container;
    }

    /** Whether a container runtime is reachable (Docker, or Podman through {@code DOCKER_HOST}). */
    public static boolean runtimeAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static ScenarioDatabase start() {
        MySQLContainer container = new MySQLContainer(DockerImageName.parse(IMAGE))
                .withUsername("root")
                .withPassword(PASSWORD)
                .withDatabaseName("wp_scenario");
        try {
            container.start();
        } catch (RuntimeException failed) {
            // the Maven wrapper disables Ryuk, so nothing else would remove a half-started container
            try {
                container.stop();
            } catch (RuntimeException cleanup) {
                failed.addSuppressed(cleanup);
            }
            throw failed;
        }
        return new ScenarioDatabase(container);
    }

    /** The host PHP connects to: an address, never {@code localhost}, which PHP's mysqli reads as a Unix socket. */
    public String host() {
        String host = container.getHost();
        return "localhost".equals(host) ? "127.0.0.1" : host;
    }

    public int port() {
        return container.getMappedPort(MySQLContainer.MYSQL_PORT);
    }

    public String user() {
        return "root";
    }

    public String password() {
        return PASSWORD;
    }

    /** The environment the PHP driver reads the server from. */
    public Map<String, String> environment() {
        return Map.of(
                "QP_DB_HOST", host(),
                "QP_DB_PORT", Integer.toString(port()),
                "QP_DB_USER", user(),
                "QP_DB_PASSWORD", password());
    }

    /** A JDBC connection to {@code database}, for checks from Java. */
    public Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:mysql://" + host() + ":" + port() + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true",
                user(),
                password());
    }

    @Override
    public void close() {
        container.stop();
    }
}
