package org.quarkpress.persistence.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkpress.persistence.NetworkScopePolicy;

class CallerCheckTest {

    static class Scope {
        static Class<?> caller() {
            return CallerCheck.callerOf(Scope.class);
        }
    }

    /** Named like the client proxy ArC generates for {@link Scope}. */
    static class Scope_ClientProxy {
        static Class<?> caller() {
            return Scope.caller();
        }
    }

    /** Named like the interception subclass ArC generates for {@link Scope}. */
    static class Scope_Subclass {
        static Class<?> caller() {
            return Scope_ClientProxy.caller();
        }
    }

    /** Shares {@link Scope}'s name as a prefix but is another class. */
    static class ScopeLookalike {
        static Class<?> caller() {
            return Scope.caller();
        }
    }

    @Test
    void theScopeAndItsGeneratedClassesAreSkipped() {
        assertThat(Scope.caller()).isEqualTo(CallerCheckTest.class);
        assertThat(Scope_ClientProxy.caller()).isEqualTo(CallerCheckTest.class);
        assertThat(Scope_Subclass.caller()).isEqualTo(CallerCheckTest.class);
    }

    @Test
    void aClassThatOnlySharesTheScopesNamePrefixIsTheCaller() {
        assertThat(ScopeLookalike.caller()).isEqualTo(ScopeLookalike.class);
    }

    @Test
    void anUnknownCallerIsAskedAboutAndNotTrusted() {
        List<Class<?>> asked = new ArrayList<>();
        NetworkScopePolicy refuses = caller -> {
            asked.add(caller);
            return false;
        };
        NetworkScopePolicy allowsUnknown = caller -> caller == null;
        assertThat(CallerCheck.isTrustedModule(null)).isFalse();
        assertThat(CallerCheck.mayOpenNetwork(null, List.of())).isFalse();
        assertThat(CallerCheck.mayOpenNetwork(null, List.of(refuses))).isFalse();
        assertThat(asked).containsExactly((Class<?>) null);
        assertThat(CallerCheck.mayOpenNetwork(null, List.of(refuses, allowsUnknown)))
                .isTrue();
        assertThat(CallerCheck.mayOpenNetwork(String.class, List.of(allowsUnknown)))
                .isFalse();
    }

    @Test
    void otherQuarkPressPackagesAreNotTrusted() {
        List<Class<?>> asked = new ArrayList<>();
        NetworkScopePolicy refuses = caller -> {
            asked.add(caller);
            return false;
        };
        assertThat(CallerCheck.isTrustedModule(CallerCheck.class)).isFalse();
        assertThat(CallerCheck.mayOpenNetwork(CallerCheck.class, List.of(refuses)))
                .isFalse();
        assertThat(asked).containsExactly(CallerCheck.class);
    }
}
