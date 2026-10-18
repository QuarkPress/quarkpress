package org.quarkpress.multisite.runtime;

import java.util.Optional;
import java.util.function.Supplier;

/** Keeps the network address for the duration of one HTTP resolution, so the strategies look it up once. */
final class NetworkNote {

    private static final class Holder {
        Optional<SiteLookup.Address> address;
    }

    private static final ThreadLocal<Holder> CURRENT = new ThreadLocal<>();

    private NetworkNote() {}

    static void begin() {
        CURRENT.set(new Holder());
    }

    static void end() {
        CURRENT.remove();
    }

    /** The network address: noted once per resolution, or loaded on every call outside one. */
    static Optional<SiteLookup.Address> network(Supplier<Optional<SiteLookup.Address>> loader) {
        Holder holder = CURRENT.get();
        if (holder == null) {
            return loader.get();
        }
        if (holder.address == null) {
            holder.address = loader.get();
        }
        return holder.address;
    }
}
