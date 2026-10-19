package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The capability reads take any site and open the network scope, so only security module code may call them: none of
 * their methods is reachable from another package.
 */
class CapabilityReadsVisibilityTest {

    @Test
    void noReadIsReachableFromAnotherPackage() {
        assertThat(Arrays.stream(CapabilityReads.class.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .filter(method ->
                                Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers()))
                        .map(Method::getName))
                .isEmpty();
    }
}
