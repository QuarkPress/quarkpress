package org.quarkpress.outbound.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AddressPolicyTest {

    /** Each refused range at its first and last address, and its neighbours outside, refused only inside a range. */
    private static final String[][] TABLE = {
        // address, "in" (refused) or "out" (allowed)
        {"0.0.0.0", "in"},
        {"0.255.255.255", "in"},
        {"1.0.0.0", "out"},
        {"9.255.255.255", "out"},
        {"10.0.0.0", "in"},
        {"10.255.255.255", "in"},
        {"11.0.0.0", "out"},
        {"100.63.255.255", "out"},
        {"100.64.0.0", "in"},
        {"100.127.255.255", "in"},
        {"100.128.0.0", "out"},
        {"126.255.255.255", "out"},
        {"127.0.0.0", "in"},
        {"127.255.255.255", "in"},
        {"128.0.0.0", "out"},
        {"169.253.255.255", "out"},
        {"169.254.0.0", "in"},
        {"169.254.255.255", "in"},
        {"169.255.0.0", "out"},
        {"172.15.255.255", "out"},
        {"172.16.0.0", "in"},
        {"172.31.255.255", "in"},
        {"172.32.0.0", "out"},
        {"191.255.255.255", "out"},
        {"192.0.0.0", "in"},
        {"192.0.0.255", "in"},
        {"192.0.1.0", "out"},
        {"192.0.1.255", "out"},
        {"192.0.2.0", "in"},
        {"192.0.2.255", "in"},
        {"192.0.3.0", "out"},
        {"192.88.98.255", "out"},
        {"192.88.99.0", "in"},
        {"192.88.99.255", "in"},
        {"192.88.100.0", "out"},
        {"192.167.255.255", "out"},
        {"192.168.0.0", "in"},
        {"192.168.255.255", "in"},
        {"192.169.0.0", "out"},
        {"198.17.255.255", "out"},
        {"198.18.0.0", "in"},
        {"198.19.255.255", "in"},
        {"198.20.0.0", "out"},
        {"198.51.99.255", "out"},
        {"198.51.100.0", "in"},
        {"198.51.100.255", "in"},
        {"198.51.101.0", "out"},
        {"203.0.112.255", "out"},
        {"203.0.113.0", "in"},
        {"203.0.113.255", "in"},
        {"203.0.114.0", "out"},
        {"223.255.255.255", "out"},
        {"224.0.0.0", "in"},
        {"239.255.255.255", "in"},
        {"240.0.0.0", "in"},
        {"255.255.255.255", "in"},
        {"8.8.8.8", "out"},
        // IPv6
        // ::/96: unspecified, loopback and IPv4-compatible, refused whatever IPv4 address they carry
        {"::", "in"},
        {"::1", "in"},
        {"::2", "in"},
        {"::7f00:1", "in"},
        {"::a00:1", "in"},
        {"::808:808", "in"},
        {"::ffff:ffff", "in"},
        {"::1:0:0", "out"},
        // 2001::/23, Teredo (2001::/32) included
        {"2000:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "out"},
        {"2001::", "in"},
        {"2001::1", "in"},
        {"2001:0:4136:e378:8000:63bf:3fff:fdd2", "in"},
        {"2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff", "in"},
        {"2001:200::", "out"},
        {"fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "out"},
        {"fc00::", "in"},
        {"fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "in"},
        {"fe00::", "out"},
        {"fe7f:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "out"},
        {"fe80::", "in"},
        {"febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "in"},
        {"fec0::", "in"},
        {"feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "in"},
        {"ff00::", "in"},
        {"ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "in"},
        {"2001:db7:ffff:ffff:ffff:ffff:ffff:ffff", "out"},
        {"2001:db8::", "in"},
        {"2001:db8:ffff:ffff:ffff:ffff:ffff:ffff", "in"},
        {"2001:db9::", "out"},
        {"ff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "out"},
        {"100::", "in"},
        {"100::ffff:ffff:ffff:ffff", "in"},
        {"100:0:0:1::", "out"},
        {"2606:4700::1111", "out"},
        // embedded IPv4, judged by the IPv4 rules
        {"64:ff9b::a00:1", "in"},
        {"64:ff9b::808:808", "out"},
        {"64:ff9b::7f00:1", "in"},
        // local-use NAT64 (64:ff9b:1::/48), refused whatever it carries
        {"64:ff9b:0:ffff:ffff:ffff:ffff:ffff", "out"},
        {"64:ff9b:1::", "in"},
        {"64:ff9b:1::a00:1", "in"},
        {"64:ff9b:1::808:808", "in"},
        {"64:ff9b:1:808:808::", "in"},
        {"64:ff9b:1:ffff:ffff:ffff:ffff:ffff", "in"},
        {"64:ff9b:2::", "out"},
        // IPv4-translated (::ffff:0:0:0/96)
        {"::ffff:0:a00:1", "in"},
        {"::ffff:0:7f00:1", "in"},
        {"::ffff:0:808:808", "out"},
        {"2002:a00:1::", "in"},
        {"2002:808:808::", "out"},
    };

    @Test
    void theTable() throws UnknownHostException {
        List<String> wrong = new ArrayList<>();
        for (String[] row : TABLE) {
            boolean expectedPublic = row[1].equals("out");
            if (AddressPolicy.isPublic(InetAddress.getByName(row[0])) != expectedPublic) {
                wrong.add(row[0]);
            }
        }
        assertThat(wrong).as("addresses judged wrongly").isEmpty();
    }

    @Test
    void mappedAddressesAreJudgedByTheirIpv4() throws UnknownHostException {
        // the JDK turns ::ffff:a.b.c.d into an IPv4 address; an Inet6Address built from the bytes stays IPv6
        assertThat(AddressPolicy.isPublic(InetAddress.getByName("::ffff:10.0.0.1")))
                .isFalse();
        assertThat(AddressPolicy.isPublic(mapped(10, 0, 0, 1))).isFalse();
        assertThat(AddressPolicy.isPublic(mapped(127, 0, 0, 1))).isFalse();
        assertThat(AddressPolicy.isPublic(mapped(8, 8, 8, 8))).isTrue();
        assertThat(AddressPolicy.isPublic(InetAddress.getByName("2002:0a00:0001::")))
                .isFalse();
    }

    private static InetAddress mapped(int a, int b, int c, int d) throws UnknownHostException {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        InetAddress address = Inet6Address.getByAddress(null, bytes, -1);
        assertThat(address).isInstanceOf(Inet6Address.class);
        return address;
    }
}
