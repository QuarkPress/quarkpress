package org.quarkpress.outbound;

import io.quarkus.arc.DefaultBean;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Resolves host names for the fetcher, once per request hop; the connection goes to an address of that answer. The
 * default asks the JDK ({@link InetAddress#getAllByName}); an application may replace it with an
 * {@code @Alternative @Priority} bean.
 */
public interface HostResolver {

    /** Every address of {@code host}, never empty. */
    List<InetAddress> resolve(String host) throws UnknownHostException;

    /** The JDK's resolver, the bean used unless an application provides another. */
    @Singleton
    @DefaultBean
    final class Jdk implements HostResolver {

        @Override
        public List<InetAddress> resolve(String host) throws UnknownHostException {
            return List.of(InetAddress.getAllByName(host));
        }
    }
}
