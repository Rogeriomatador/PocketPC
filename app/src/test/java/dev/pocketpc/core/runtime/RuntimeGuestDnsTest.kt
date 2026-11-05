package dev.pocketpc.core.runtime

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class RuntimeGuestDnsTest {
    @Test fun preservesNetworkDnsAndDeduplicates() {
        val ip = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
        assertEquals("nameserver 10.0.0.2\noptions timeout:2 attempts:2\n", RuntimeGuestDns.configuration(listOf(ip, ip)))
    }
    @Test fun offlineNeverFallsBackToInventedDns() {
        assertNull(RuntimeGuestDns.configuration(emptyList()))
        assertNull(RuntimeGuestDns.configuration(listOf(InetAddress.getByAddress(ByteArray(4)))))
    }
}
