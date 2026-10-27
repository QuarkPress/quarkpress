package org.quarkpress.devui.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class DevThreadsTest {

    @Test
    void workRunsOnAVirtualThreadOfItsOwnWithTheCallersClassLoader() {
        Thread caller = Thread.currentThread();
        ClassLoader original = caller.getContextClassLoader();
        ClassLoader loader = new ClassLoader(original) {};
        caller.setContextClassLoader(loader);
        try {
            Thread worker = DevThreads.detached(Thread::currentThread);
            assertThat(worker).isNotSameAs(caller);
            assertThat(worker.isVirtual()).isTrue();
            assertThat(worker.getName()).isEqualTo("quarkpress-dev-ui");
            assertThat(DevThreads.detached(() -> Thread.currentThread().getContextClassLoader()))
                    .isSameAs(loader);
        } finally {
            caller.setContextClassLoader(original);
        }
    }

    @Test
    void theWorkerInheritsNoThreadLocalOfTheCaller() {
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller");
        try {
            assertThat(DevThreads.detached(inherited::get)).isNull();
        } finally {
            inherited.remove();
        }
    }

    @Test
    void aRuntimeExceptionComesBackUnchangedAndACheckedOneWrapped() {
        IllegalArgumentException boom = new IllegalArgumentException("boom");
        assertThatThrownBy(() -> DevThreads.detached(() -> {
                    throw boom;
                }))
                .isSameAs(boom);
        IOException io = new IOException("io");
        assertThatThrownBy(() -> DevThreads.detached(() -> {
                    throw io;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(io);
    }
}
