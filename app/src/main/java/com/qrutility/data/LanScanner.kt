package com.qrutility.data

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class Discovered(val host: String, val port: Int, val type: DbType)

/**
 * Best-effort discovery of databases on the local /24 by probing the common
 * DB ports (Postgres 5432, MySQL/MariaDB 3306) on every host. Blocking — run
 * from [DbExecutor].
 */
object LanScanner {

    private val PORTS = listOf(5432 to DbType.POSTGRES, 3306 to DbType.MYSQL)

    /** A site-local IPv4 of this device, or null if not on a LAN. */
    fun localIpv4(): String? {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) { /* ignore */ }
        return null
    }

    fun discover(timeoutMs: Int = 300): List<Discovered> {
        val ip = localIpv4() ?: return emptyList()
        val base = ip.substringBeforeLast('.') + "."
        val pool = Executors.newFixedThreadPool(64)
        val found = Collections.synchronizedList(ArrayList<Discovered>())
        try {
            val tasks = ArrayList<Future<*>>()
            for (h in 1..254) {
                val host = base + h
                for ((port, type) in PORTS) {
                    tasks.add(pool.submit {
                        try {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(host, port), timeoutMs)
                                found.add(Discovered(host, port, type))
                            }
                        } catch (e: Exception) { /* closed / filtered */ }
                    })
                }
            }
            tasks.forEach { runCatching { it.get(2, TimeUnit.SECONDS) } }
        } finally {
            pool.shutdownNow()
        }
        return found.sortedWith(
            compareBy({ it.host.substringAfterLast('.').toIntOrNull() ?: 0 }, { it.port })
        )
    }
}
