package org.quarkword.keycloak.provisioner.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.InMemoryLogHandler;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.security.ImportedUser;
import org.quarkword.security.SystemIdentity;
import org.quarkword.security.UserImports;

/** Nothing logged while users are imported with a hash, at every level, carries the hash. */
class NoSecretsInLogsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = ProvisionerApp.app(NoSecretsInLogsDbTest.class)
            .overrideConfigKey("quarkus.log.min-level", "TRACE")
            .overrideRuntimeConfigKey("quarkus.log.level", "ALL")
            // the records are read by the test; the console keeps its usual level
            .overrideRuntimeConfigKey("quarkus.log.console.level", "INFO");

    @Inject
    UserImports imports;

    @Inject
    SystemIdentity system;

    @Test
    void theHashIsInNoLogRecord() throws Exception {
        InMemoryLogHandler handler = new InMemoryLogHandler(record -> true);
        handler.setLevel(Level.ALL);
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        ImportedUser created;
        ImportedUser again;
        try {
            String name = ProvisionerApp.username();
            created = system.call(() -> imports.importUser(ProvisionerApp.john(name, ProvisionerApp.PHPASS)));
            // the existing-user path too
            again = system.call(() -> imports.importUser(ProvisionerApp.john(name, ProvisionerApp.PHPASS_OTHER)));
        } finally {
            root.removeHandler(handler);
        }

        assertThat(created.createdInIdentityProvider()).isTrue();
        assertThat(again.createdInIdentityProvider()).isFalse();
        List<LogRecord> records = handler.getRecords();
        // the capture saw the requests: the REST client and Keycloak's admin client log at DEBUG and below
        assertThat(records).isNotEmpty();
        for (LogRecord record : records) {
            assertThat(text(record)).doesNotContain(ProvisionerApp.PHPASS).doesNotContain(ProvisionerApp.PHPASS_OTHER);
        }
    }

    /** The record's template, parameters and stack trace: together, everything its formatted text can hold. */
    private static String text(LogRecord record) {
        StringBuilder text = new StringBuilder(record.getLoggerName() + " " + record.getMessage());
        Object[] parameters = record.getParameters();
        if (parameters != null) {
            for (Object parameter : parameters) {
                text.append(' ').append(parameter);
            }
        }
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            text.append(' ').append(trace);
        }
        return text.toString();
    }
}
