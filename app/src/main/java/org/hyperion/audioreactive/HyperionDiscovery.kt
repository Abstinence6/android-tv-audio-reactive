package org.hyperion.audioreactive

import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A server is retained only when a read-only API reply supplies an immutable UUID and both ports validate. */
data class HyperionDevice(
    val identity: String,
    val name: String,
    val host: String,
    val jsonPort: Int = HyperionDiscovery.JSON_PORT,
    val dataPort: Int = HyperionDiscovery.DATA_PORT,
) {
    fun valid(): Boolean = isHyperionIdentity(identity) && name.isNotBlank() && HyperionDiscovery.isPermittedHost(host) &&
        jsonPort == HyperionDiscovery.JSON_PORT && dataPort == HyperionDiscovery.DATA_PORT
}

object HyperionInventory {
    fun merge(previous: List<HyperionDevice>, discovered: List<HyperionDevice>): List<HyperionDevice> {
        val fresh = discovered.filter(HyperionDevice::valid).associateBy { it.identity }
        val previousIds = previous.map { it.identity }.toSet()
        return (previous.map { fresh[it.identity] ?: it } + discovered.filter { it.identity !in previousIds })
            .filter(HyperionDevice::valid).distinctBy { it.identity }.take(64)
    }
}

/** JVM-safe parser and bounded, read-only scanner. It never writes a Hyperion setting or frame. */
object HyperionDiscovery {
    const val JSON_PORT = 19444
    const val DATA_PORT = 19400
    private const val CONNECT_TIMEOUT_MS = 350
    private const val READ_TIMEOUT_MS = 700
    private const val CONCURRENCY = 12

    /**
     * Hyperion uses a plaintext JSON/TCP protocol. Discovery is therefore restricted to the
     * previously verified appliance, matching the app network-security policy. A new endpoint
     * requires a reviewed update rather than a LAN-wide cleartext exception.
     */
    private const val TRUSTED_HYPERION_HOST = "192.168.1.158"
    fun isPermittedHost(host: String): Boolean = host == TRUSTED_HYPERION_HOST

    /**
     * Prefer a UUID returned by serverinfo. Current Hyperion versions may omit it there, so use
     * only the documented immutable `info.hyperion.id` from a separate read-only sysinfo reply.
     * Hostname/friendly name and mutable instance ids never identify a route.
     */
    fun parseServerInfo(host: String, response: String, sysInfo: String? = null): HyperionDevice? {
        if (!isPermittedHost(host)) return null
        val uuid = serverInfoUuid(response) ?: sysInfo?.let(::sysInfoHyperionId) ?: return null
        val name = Regex("""\"(?:hostname|name|friendly_name)\"\s*:\s*\"([^\"]+)\"""")
            .find(response)?.groupValues?.get(1)?.trim()?.take(80).orEmpty().ifBlank { "Hyperion" }
        return HyperionDevice("uuid:$uuid", name, host).takeIf(HyperionDevice::valid)
    }

    /** Scan only the endpoint permitted by the cleartext policy. */
    fun scan(): List<HyperionDevice> {
        val device = validate(TRUSTED_HYPERION_HOST)
        return if (device == null) emptyList() else listOf(device)
    }

    /** Revalidation opens only the JSON and FlatBuffers TCP services and never sends frame data. */
    fun revalidate(devices: Collection<HyperionDevice>): List<HyperionDevice> = devices.mapNotNull { saved ->
        validate(saved.host)?.takeIf { fresh -> fresh.identity == saved.identity && fresh.jsonPort == saved.jsonPort && fresh.dataPort == saved.dataPort }
    }

    private fun validate(host: String): HyperionDevice? {
        return validate(host, ::serverInfo, ::sysInfo, ::verifyDataPort)
    }

    /** Kept injectable so fallback behavior can be tested without opening sockets. */
    internal fun validate(
        host: String,
        requestServerInfo: (String) -> String?,
        requestSysInfo: (String) -> String?,
        verifyDataPort: (String) -> Boolean,
    ): HyperionDevice? {
        if (!isPermittedHost(host)) return null
        val response = requestServerInfo(host) ?: return null
        val sysInfo = if (serverInfoUuid(response) == null) requestSysInfo(host) else null
        val device = parseServerInfo(host, response, sysInfo) ?: return null
        return device.takeIf { verifyDataPort(host) }
    }

    private fun serverInfoUuid(response: String): String? = Regex("""\"(?:uuid|server_uuid|serverUuid)\"\s*:\s*\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"""")
        .find(response)?.groupValues?.get(1)?.lowercase()

    private fun sysInfoHyperionId(response: String): String? = JsonValueParser.stringAt(response, "info", "hyperion", "id")
        ?.takeIf(::isUuid)?.lowercase()

    private fun serverInfo(host: String): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, JSON_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write("{\"command\":\"serverinfo\"}\n"); writer.flush()
            BufferedReader(socket.getInputStream().reader()).use { it.readLine()?.take(32_768) }
        }
    } catch (_: Exception) { null }

    private fun sysInfo(host: String): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, JSON_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write("{\"command\":\"sysinfo\"}\n"); writer.flush()
            BufferedReader(socket.getInputStream().reader()).use { it.readLine()?.take(32_768) }
        }
    } catch (_: Exception) { null }

    private fun verifyDataPort(host: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, DATA_PORT), CONNECT_TIMEOUT_MS) }; true
    } catch (_: Exception) { false }
}

/** Small JSON parser used only for exact identity-path lookup; it accepts no partial JSON. */
private object JsonValueParser {
    fun stringAt(json: String, vararg path: String): String? = runCatching {
        var value: Value = Parser(json).parse()
        for (segment in path) value = (value as? Value.Object)?.members?.get(segment) ?: return null
        (value as? Value.StringValue)?.value
    }.getOrNull()

    private sealed class Value {
        class Object(val members: Map<String, Value>) : Value()
        class StringValue(val value: String) : Value()
        data object Other : Value()
    }

    private class Parser(private val input: String) {
        private var index = 0

        fun parse(): Value {
            val value = value()
            whitespace()
            check(index == input.length) { "trailing JSON data" }
            return value
        }

        private fun value(): Value {
            whitespace()
            return when (input.getOrNull(index)) {
                '{' -> obj()
                '[' -> array()
                '"' -> Value.StringValue(string())
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> error("invalid JSON value")
            }
        }

        private fun obj(): Value.Object {
            take('{'); whitespace()
            val members = linkedMapOf<String, Value>()
            if (takeIf('}')) return Value.Object(members)
            while (true) {
                whitespace(); val key = string(); whitespace(); take(':')
                members[key] = value(); whitespace()
                if (takeIf('}')) return Value.Object(members)
                take(',')
            }
        }

        private fun array(): Value {
            take('['); whitespace()
            if (takeIf(']')) return Value.Other
            while (true) {
                value(); whitespace()
                if (takeIf(']')) return Value.Other
                take(',')
            }
        }

        private fun string(): String {
            take('"')
            val output = StringBuilder()
            while (true) when (val char = input.getOrNull(index++) ?: error("unterminated string")) {
                '"' -> return output.toString()
                '\\' -> output.append(escape())
                in '\u0000'..'\u001f' -> error("control character in string")
                else -> output.append(char)
            }
        }

        private fun escape(): Char = when (val escaped = input.getOrNull(index++) ?: error("bad escape")) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
            'u' -> input.substring(index, index + 4).also { index += 4 }.toInt(16).toChar()
            else -> error("bad escape")
        }

        private fun literal(expected: String): Value.Other { take(expected); return Value.Other }
        private fun number(): Value.Other {
            val start = index
            if (input.getOrNull(index) == '-') index++
            if (input.getOrNull(index) == '0') index++ else digits()
            if (input.getOrNull(index) == '.') { index++; digits() }
            if (input.getOrNull(index)?.let { it in charArrayOf('e', 'E') } == true) { index++; if (input.getOrNull(index)?.let { it in charArrayOf('+', '-') } == true) index++; digits() }
            check(index > start) { "bad number" }; return Value.Other
        }
        private fun digits() { val start = index; while (input.getOrNull(index)?.let { it in '0'..'9' } == true) index++; check(index > start) { "expected digits" } }
        private fun whitespace() { while (input.getOrNull(index)?.let { it in charArrayOf(' ', '\n', '\r', '\t') } == true) index++ }
        private fun take(expected: Char) { check(input.getOrNull(index++) == expected) { "expected $expected" } }
        private fun take(expected: String) { check(input.regionMatches(index, expected, 0, expected.length)) { "expected $expected" }; index += expected.length }
        private fun takeIf(expected: Char): Boolean = (input.getOrNull(index) == expected).also { if (it) index++ }
    }
}

private fun isUuid(value: String): Boolean = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)

fun isHyperionIdentity(identity: String): Boolean = Regex("uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(identity)
