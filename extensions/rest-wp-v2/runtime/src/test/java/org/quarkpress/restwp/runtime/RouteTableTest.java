package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RouteTableTest {

    @Test
    void routesLongerThan2048Utf8BytesAreNotMatched() {
        assertThat(RouteTable.tooLong("/" + "a".repeat(2047))).isFalse();
        assertThat(RouteTable.tooLong("/" + "a".repeat(2048))).isTrue();
        String eAcute = new String(Character.toChars(0xE9));
        // 1 + 2 * 1023 = 2047 bytes, then 2049
        assertThat(RouteTable.tooLong("/" + eAcute.repeat(1023))).isFalse();
        assertThat(RouteTable.tooLong("/" + eAcute.repeat(1024))).isTrue();
        String euro = new String(Character.toChars(0x20AC));
        assertThat(RouteTable.tooLong("/" + euro.repeat(682))).isFalse();
        assertThat(RouteTable.tooLong("/" + euro.repeat(683))).isTrue();
        String emoji = new String(Character.toChars(0x1F600));
        assertThat(RouteTable.tooLong("/" + emoji.repeat(511) + "abc")).isFalse();
        assertThat(RouteTable.tooLong("/" + emoji.repeat(512))).isTrue();
    }
}
