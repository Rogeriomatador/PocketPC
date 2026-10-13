package dev.pocketpc.core.runtime

import java.net.InetAddress

object RuntimeGuestDns {
    /** Uses the active Android network (including VPN); never invents a public DNS server. */
    fun configuration(servers: List<InetAddress>): String? {
        val addresses = servers.filterNot { it.isAnyLocalAddress || it.isMulticastAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.takeIf { address -> '%' !in address } }
            .distinct().take(3)
        if (addresses.isEmpty()) return null
        return addresses.joinToString(separator = "\n", postfix = "\noptions timeout:2 attempts:2\n") { "nameserver $it" }
    }
}
