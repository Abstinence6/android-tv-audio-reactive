package org.hyperion.audioreactive

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
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

    fun isPrivateLanIpv4(host: String): Boolean = try {
        val address = InetAddress.getByName(host) as? Inet4Address ?: return false
        val b = address.address.map { it.toInt() and 0xff }
        b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
    } catch (_: Exception) { false }

    /**
     * WLED has no HTTPS API in this installation and Android NSC cannot grant cleartext per
     * dynamically discovered device. Keep the exception intentionally narrow: only the two
     * previously verified local controllers may be queried over HTTP. New controllers require a
     * reviewed app update rather than silently widening the plaintext trust boundary.
     */
    private val TRUSTED_WLED_HOSTS = setOf("192.168.1.152", "192.168.1.153")
    fun isCleartextWledHost(host: String): Boolean = host in TRUSTED_WLED_HOSTS

    fun parseInfo(host: String, info: String, state: String? = null): WledDevice? {
        if (!isCleartextWledHost(host)) return null
        val leds = number(info, "count") ?: return null
        val mac = string(info, "mac")?.replace(Regex("[^0-9A-Fa-f]"), "")?.uppercase()
            ?.takeIf { it.length == 12 } ?: return null
        val jsonName = string(info, "name") ?: string(state ?: "", "name")
        val name = jsonName ?: "WLED $host"
        val port = number(info, "udpport") ?: number(state ?: "", "udpport") ?: DEFAULT_REALTIME_PORT
        return WledDevice("mac:$mac", name.trim().take(80), host, leds, port).takeIf { it.valid() }
    }

    /** Read-only bounded local scan. It probes only HTTP-policy-approved private IPv4 /24 hosts. */
    fun scan(): List<WledDevice> {
        val hosts = localHosts()
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

    private fun validate(host: String): WledDevice? {
        if (!isCleartextWledHost(host)) return null
        return try {
            val info = read(host, "/json/info") ?: return null
            parseInfo(host, info, read(host, "/json/state"))
        } catch (_: Exception) { null }
    }

    private fun read(host: String, path: String): String? {
        if (!isCleartextWledHost(host)) return null
        val connection = (URL("http://$host$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS; requestMethod = "GET"; instanceFollowRedirects = false
        }
        return try { if (connection.responseCode == 200) connection.inputStream.bufferedReader().use { it.readText().take(16_384) } else null } finally { connection.disconnect() }
    }

    /** Read-only discovery is constrained to the exact endpoints granted cleartext policy. */
    private fun localHosts(): List<String> = TRUSTED_WLED_HOSTS.toList()

    private fun string(json: String, key: String): String? = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1)
    private fun number(json: String, key: String): Int? = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
}

fun isMacIdentity(identity: String): Boolean = Regex("mac:[0-9A-F]{12}").matches(identity)
fun isPrivateLanIpv4(host: String) = WledDiscovery.isPrivateLanIpv4(host)
