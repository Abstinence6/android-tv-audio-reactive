package org.hyperion.audioreactive

import android.content.Context

import java.net.HttpURLConnection
import java.net.Inet4Address

import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A validated WLED endpoint. It is never created from user-entered network data. */
data class WledDevice(val identity: String, val name: String, val host: String, val leds: Int, val realtimePort: Int) {
    fun valid(): Boolean = isMacIdentity(identity) && name.isNotBlank() && WledDiscovery.isCleartextWledHost(host) && leds in 1..4096 && realtimePort in 1..65535
}

/** Parsing and host policy are JVM-safe so they can be tested without making network requests. */
object WledDiscovery {
    const val DEFAULT_REALTIME_PORT = 21324
    private const val CONNECT_TIMEOUT_MS = 350
    private const val READ_TIMEOUT_MS = 600
    private const val MAX_HOSTS = 512
    private const val CONCURRENCY = 12

    /** Literal IPv4 only: no DNS resolution can turn a hostname into a LAN target. */
    private fun ipv4Bytes(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } }) return null
        return parts.map { it.toIntOrNull()?.takeIf { number -> number in 0..255 } ?: return null }.toIntArray()
    }
    fun isPrivateLanIpv4(host: String): Boolean = ipv4Bytes(host)?.let { b ->
        b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
    } == true

    /** Android NSC cannot describe dynamic RFC1918 subnets. Cleartext is globally permitted, but HTTP is accepted only for literal private IPv4 endpoints below. */
    fun isCleartextWledHost(host: String): Boolean = isPrivateLanIpv4(host)

    fun parseInfo(host: String, info: String, state: String? = null): WledDevice? {
        if (!isCleartextWledHost(host)) return null
        val leds = number(info, "count") ?: return null
        val mac = string(info, "mac")?.replace(Regex("[^0-9A-Fa-f]"), "")?.uppercase()?.takeIf { it.length == 12 } ?: return null
        val name = string(info, "name") ?: string(state ?: "", "name") ?: "WLED $host"
        val port = number(info, "udpport") ?: number(state ?: "", "udpport") ?: DEFAULT_REALTIME_PORT
        return WledDevice("mac:$mac", name.trim().take(80), host, leds, port).takeIf { it.valid() }
    }

    /**
     * Read-only bounded scan across every reachable RFC1918 IPv4 interface subnet.
     * The saved cursor advances each subnet fairly: a /8 never starves a smaller LAN and,
     * over repeated scans, every usable host in every reachable subnet is attempted.
     */
    fun scan(context: Context): List<WledDevice> {
        val networks = localNetworks()
        val preferences = context.applicationContext.getSharedPreferences("wled_discovery_cursor_v1", Context.MODE_PRIVATE)
        val batch = hostBatch(networks) { key -> preferences.getLong(key, 0L) }
        preferences.edit().also { editor -> batch.nextCursors.forEach { (key, value) -> editor.putLong(key, value) } }.apply()
        val hosts = batch.hosts
        val pool = Executors.newFixedThreadPool(CONCURRENCY)
        return try {
            pool.invokeAll(hosts.map { host -> Callable { validate(host) } }, 3, TimeUnit.SECONDS)
                .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
                .distinctBy { it.identity }
        } finally { pool.shutdownNow() }
    }

    /** Revalidation performs read-only HTTP only; it never opens UDP output sockets. */
    fun revalidate(devices: Collection<WledDevice>): List<WledDevice> = devices.mapNotNull { saved ->
        validate(saved.host)?.takeIf { fresh -> fresh.identity == saved.identity && fresh.leds == saved.leds && fresh.realtimePort == saved.realtimePort }
    }

    /** Compatibility/test view of the first bounded fair batch. */
    internal fun hostsForNetworks(networks: List<Pair<String, Int>>): List<String> = hostBatch(networks) { 0L }.hosts

    internal data class HostBatch(val hosts: List<String>, val nextCursors: Map<String, Long>)

    internal fun hostBatch(networks: List<Pair<String, Int>>, cursorFor: (String) -> Long): HostBatch {
        val hosts = linkedSetOf<String>()
        data class RangeCursor(val key: String, val first: Long, val last: Long, var cursor: Long, var emitted: Long = 0)
        val ranges = networks.mapNotNull { (address, prefix) ->
            val bytes = ipv4Bytes(address) ?: return@mapNotNull null
            if (!isPrivateLanIpv4(address) || prefix !in 8..30) return@mapNotNull null
            val value = bytes.fold(0L) { acc, byte -> (acc shl 8) or byte.toLong() }
            val mask = (-1L shl (32 - prefix)) and 0xffffffffL
            val first = (value and mask) + 1
            val last = (value or (0xffffffffL ushr prefix)) - 1
            val key = "${first}-${last}"
            RangeCursor(key, first, last, cursorFor(key).coerceAtLeast(0L) % (last - first + 1))
        }
        while (hosts.size < MAX_HOSTS && ranges.isNotEmpty()) {
            var added = false
            ranges.forEach { range ->
                if (hosts.size < MAX_HOSTS && range.emitted < range.last - range.first + 1) {
                    val candidate = range.first + ((range.cursor + range.emitted) % (range.last - range.first + 1))
                    hosts += listOf(24, 16, 8, 0).joinToString(".") { shift -> ((candidate ushr shift) and 255).toString() }
                    range.emitted++
                    added = true
                }
            }
            if (!added) break
        }
        return HostBatch(hosts.toList(), ranges.associate { range -> range.key to ((range.cursor + range.emitted) % (range.last - range.first + 1)) })
    }

    private fun localNetworks(): List<Pair<String, Int>> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { iface ->
                iface.interfaceAddresses.mapNotNull { entry ->
                    val address = entry.address as? Inet4Address ?: return@mapNotNull null
                    address.hostAddress?.let { host -> host to entry.networkPrefixLength.toInt() }
                }
            }
        }.getOrDefault(emptyList())

    private fun validate(host: String): WledDevice? {
        if (!isCleartextWledHost(host)) return null
        return try { parseInfo(host, read(host, "/json/info") ?: return null, read(host, "/json/state")) } catch (_: Exception) { null }
    }
    private fun read(host: String, path: String): String? {
        if (!isCleartextWledHost(host) || path !in setOf("/json/info", "/json/state")) return null
        val connection = (URL("http://$host$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS; requestMethod = "GET"; instanceFollowRedirects = false
        }
        return try { if (connection.responseCode == 200) connection.inputStream.bufferedReader().use { it.readText().take(16_384) } else null } finally { connection.disconnect() }
    }
    private fun string(json: String, key: String): String? = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1)
    private fun number(json: String, key: String): Int? = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
}
fun isMacIdentity(identity: String): Boolean = Regex("mac:[0-9A-F]{12}").matches(identity)
fun isPrivateLanIpv4(host: String) = WledDiscovery.isPrivateLanIpv4(host)
