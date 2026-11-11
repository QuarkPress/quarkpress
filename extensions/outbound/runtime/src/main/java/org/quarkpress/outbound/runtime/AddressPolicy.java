package org.quarkpress.outbound.runtime;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Which addresses outbound requests may reach: public unicast addresses only. Refused are the IPv4 ranges for "this
 * network", private networks, shared address space, loopback, link-local, protocol assignments, documentation,
 * 6to4 relays, benchmarking, multicast and the reserved block; the IPv6 unspecified, loopback and IPv4-compatible
 * addresses ({@code ::/96}), the IETF protocol assignments ({@code 2001::/23}, Teredo included), the local-use NAT64
 * prefix ({@code 64:ff9b:1::/48}, whose shorter prefixes place an IPv4 address anywhere inside it), unique local,
 * link-local, site-local, multicast, documentation and discard-only prefixes; and IPv6 addresses that carry an IPv4
 * address (mapped, translated, NAT64 well-known prefix, 6to4), judged by that IPv4 address.
 */
public final class AddressPolicy {

    /** Refused IPv4 ranges: network, prefix length. */
    private static final int[][] IPV4 = {
        {ipv4(0, 0, 0, 0), 8},
        {ipv4(10, 0, 0, 0), 8},
        {ipv4(100, 64, 0, 0), 10},
        {ipv4(127, 0, 0, 0), 8},
        {ipv4(169, 254, 0, 0), 16},
        {ipv4(172, 16, 0, 0), 12},
        {ipv4(192, 0, 0, 0), 24},
        {ipv4(192, 0, 2, 0), 24},
        {ipv4(192, 88, 99, 0), 24},
        {ipv4(192, 168, 0, 0), 16},
        {ipv4(198, 18, 0, 0), 15},
        {ipv4(198, 51, 100, 0), 24},
        {ipv4(203, 0, 113, 0), 24},
        {ipv4(224, 0, 0, 0), 4},
        {ipv4(240, 0, 0, 0), 4},
    };

    /** Refused IPv6 prefixes: the leading bytes, prefix length. */
    private static final Object[][] IPV6 = {
        {bytes(0x20, 0x01, 0x00), 23},
        {bytes(0xfc), 7},
        {bytes(0xfe, 0x80), 10},
        {bytes(0xfe, 0xc0), 10},
        {bytes(0xff), 8},
        {bytes(0x20, 0x01, 0x0d, 0xb8), 32},
        {bytes(0x01, 0x00, 0, 0, 0, 0, 0, 0), 64},
    };

    private static final byte[] NAT64 = bytes(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0);

    private static final byte[] NAT64_LOCAL = bytes(0x00, 0x64, 0xff, 0x9b, 0x00, 0x01);

    private AddressPolicy() {}

    /** Whether {@code address} is a public unicast address. */
    public static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicIpv4(bytes, 0);
        }
        if (allZeroBefore(bytes, 12)) {
            return false; // unspecified, loopback, IPv4-compatible
        }
        if (allZeroBefore(bytes, 10) && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
            return isPublicIpv4(bytes, 12); // IPv4-mapped
        }
        if (allZeroBefore(bytes, 8)
                && bytes[8] == (byte) 0xff
                && bytes[9] == (byte) 0xff
                && bytes[10] == 0
                && bytes[11] == 0) {
            return isPublicIpv4(bytes, 12); // IPv4-translated
        }
        if (startsWith(bytes, NAT64_LOCAL, 48)) {
            return false; // local-use NAT64: where the IPv4 address sits depends on the network's prefix
        }
        if (startsWith(bytes, NAT64, 96)) {
            return isPublicIpv4(bytes, 12); // NAT64, well-known prefix
        }
        if (bytes[0] == 0x20 && bytes[1] == 0x02) {
            return isPublicIpv4(bytes, 2); // 6to4
        }
        for (Object[] prefix : IPV6) {
            if (startsWith(bytes, (byte[]) prefix[0], (Integer) prefix[1])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPublicIpv4(byte[] bytes, int offset) {
        int value = ipv4(
                bytes[offset] & 0xff, bytes[offset + 1] & 0xff, bytes[offset + 2] & 0xff, bytes[offset + 3] & 0xff);
        for (int[] range : IPV4) {
            int mask = -1 << (32 - range[1]);
            if ((value & mask) == range[0]) {
                return false;
            }
        }
        return true;
    }

    private static boolean allZeroBefore(byte[] bytes, int end) {
        for (int i = 0; i < end; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix, int bits) {
        for (int bit = 0; bit < bits; bit += 8) {
            int i = bit / 8;
            int width = Math.min(8, bits - bit);
            int mask = (0xff << (8 - width)) & 0xff;
            if ((bytes[i] & mask) != (prefix[i] & mask)) {
                return false;
            }
        }
        return true;
    }

    private static int ipv4(int a, int b, int c, int d) {
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }
}
