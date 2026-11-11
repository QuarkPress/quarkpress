package org.quarkpress.outbound.deployment;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.quarkpress.outbound.HostResolver;

/**
 * A resolver that answers from a table instead of DNS: a name maps to a list of answers, the first used for the first
 * question and the last for every later one (a host that rebinds), optionally after a delay. IP literals made of
 * digits and dots are parsed; any other name is unknown, so no test reaches a real resolver.
 */
@Alternative
@Priority(1)
@Singleton
public class MappedResolver implements HostResolver {

    private final Map<String, List<List<InetAddress>>> answers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, Long> delays = new ConcurrentHashMap<>();

    /** Maps {@code host} to {@code addresses} for every question. */
    public void map(String host, String... addresses) {
        answers.put(host, List.of(addresses(addresses)));
        calls.remove(host);
        delays.remove(host);
    }

    /** Maps {@code host} to {@code addresses} for every question, for addresses no literal gives (IPv4-mapped IPv6). */
    public void map(String host, InetAddress... addresses) {
        answers.put(host, List.of(List.of(addresses)));
        calls.remove(host);
    }

    /** Makes every question about {@code host} take {@code millis} before it is answered with {@code address}. */
    public void slow(String host, long millis, String address) {
        map(host, address);
        delays.put(host, millis);
    }

    /** Answers {@code first} to the first question about {@code host}, {@code later} to every other one. */
    public void rebind(String host, String first, String later) {
        answers.put(host, List.of(addresses(first), addresses(later)));
        calls.remove(host);
    }

    /** How often {@code host} was asked about since it was mapped. */
    public int calls(String host) {
        AtomicInteger count = calls.get(host);
        return count == null ? 0 : count.get();
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        int call = calls.computeIfAbsent(host, key -> new AtomicInteger()).getAndIncrement();
        Long delay = delays.get(host);
        if (delay != null) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UnknownHostException(host + " (interrupted)");
            }
        }
        List<List<InetAddress>> answer = answers.get(host);
        if (answer != null) {
            return answer.get(Math.min(call, answer.size() - 1));
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                throw new UnknownHostException(host);
            }
        }
        return List.of(InetAddress.getByName(host));
    }

    private static List<InetAddress> addresses(String... literals) {
        List<InetAddress> list = new ArrayList<>();
        for (String literal : literals) {
            try {
                list.add(InetAddress.getByName(literal));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(literal, e);
            }
        }
        return List.copyOf(list);
    }
}
