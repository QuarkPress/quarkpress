package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.security.NewUser;
import org.quarkpress.security.ProvisionedIdentity;
import org.quarkpress.security.UserProvisioningException;

class UserImportsTransactionTest {

    private static final NewUser USER =
            new NewUser("john", Optional.empty(), "John", Optional.empty(), Optional.empty(), Optional.empty());

    @Test
    void aProvisionerFailureIsKeptWhenTheResumeFailsToo() {
        DefaultUserImports imports = new DefaultUserImports();
        imports.transactions = suspendingManagerThatCannotResume();
        UserProvisioningException refused =
                new UserProvisioningException(UserProvisioningException.Reason.UNAVAILABLE, "unavailable");
        imports.provisioner = user -> {
            throw refused;
        };

        assertThatThrownBy(() -> imports.outsideTransaction(USER)).isSameAs(refused);
        assertThat(refused.getSuppressed()).singleElement().isInstanceOf(SystemException.class);
    }

    @Test
    void aResumeFailureAfterASuccessIsReported() {
        DefaultUserImports imports = new DefaultUserImports();
        imports.transactions = suspendingManagerThatCannotResume();
        imports.provisioner = user -> new ProvisionedIdentity("subject", true);

        assertThatThrownBy(() -> imports.outsideTransaction(USER))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(SystemException.class);
    }

    private static TransactionManager suspendingManagerThatCannotResume() {
        Transaction transaction = (Transaction) Proxy.newProxyInstance(
                Transaction.class.getClassLoader(), new Class<?>[] {Transaction.class}, (proxy, method, args) -> null);
        return (TransactionManager) Proxy.newProxyInstance(
                TransactionManager.class.getClassLoader(),
                new Class<?>[] {TransactionManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "suspend" -> transaction;
                    case "resume" -> throw new SystemException("resume failed");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
