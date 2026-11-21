package eu.kanade.tachiyomi.network

import dev.kathttp3.ResolvedAddress
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Base64

/** Parsed public ECH bootstrap values, keeping the historical TXT field schema. */
data class EchResolverBootstrap(
    val dohEndpoints: List<String>,
    val txtDohEndpoints: List<String>,
    val edgeIps: List<String>,
    val echConfig: ByteArray?,
) {
    companion object {
        fun parseTxt(
            pieces: Iterable<String>,
            configuredEndpoints: Iterable<String>,
            configuredIps: Iterable<String>,
        ): EchResolverBootstrap {
            val values = linkedMapOf<String, String>()
            pieces.joinToString("\n") { normalizeTxtPiece(it) }
                .split(';', '\n')
                .forEach { item ->
                    val separator = item.indexOf('=')
                    if (separator > 0) {
                        values[item.substring(0, separator).trim().lowercase()] =
                            item.substring(separator + 1).trim()
                    }
                }

            val txtEndpoints = listOfNotNull(values["doh"], values["doh2"], values["doh3"])
                .flatMap(::splitCommaList)
                .mapNotNull(::validatedHttpsEndpoint)
                .distinct()
            val endpoints = if (txtEndpoints.isNotEmpty()) txtEndpoints else
                configuredEndpoints.mapNotNull(::validatedHttpsEndpoint).distinct()
            val txtIps = splitCommaList(values["ip"] ?: values["ips"].orEmpty())
                .filter(::isLiteralIp)
                .distinct()
            val fallbackIps = configuredIps.flatMap(::splitCommaList)
                .filter(::isLiteralIp)
                .distinct()
            val encodedEch = (values["ech"] ?: values["echconfig"]).orEmpty()
            return EchResolverBootstrap(
                dohEndpoints = endpoints,
                txtDohEndpoints = txtEndpoints,
                edgeIps = txtIps.ifEmpty { fallbackIps },
                echConfig = decodeEchConfig(encodedEch),
            )
        }

        fun parseEndpointList(value: String): List<String> = splitCommaList(value)
            .mapNotNull(::validatedHttpsEndpoint)
            .distinct()

        fun isLiteralIp(value: String): Boolean {
            val ip = value.trim()
            if (ip.isEmpty() || '%' in ip) return false
            if (':' !in ip) {
                val octets = ip.split('.')
                return octets.size == 4 && octets.all { octet ->
                    octet.isNotEmpty() && octet.all(Char::isDigit) &&
                        octet.toIntOrNull()?.let { it in 0..255 } == true
                }
            }
            if (!ip.matches(Regex("[0-9A-Fa-f:.]+"))) return false
            return runCatching { InetAddress.getByName(ip) is Inet6Address }.getOrDefault(false)
        }

        /** Uses a validated literal IP only when DNS supplied no usable address. */
        fun addressesWithFallback(
            dnsAddresses: List<ResolvedAddress>,
            edgeIps: List<String>,
            port: Int,
            fallbackEchConfig: ByteArray?,
        ): List<ResolvedAddress> {
            val dns = dnsAddresses.filter { isLiteralIp(it.ip) }
            val selected = dns.ifEmpty {
                edgeIps.filter(::isLiteralIp).distinct().map { ResolvedAddress(it, port) }
            }
            return selected.map { address ->
                if (address.echConfig != null || fallbackEchConfig == null) address
                else address.copy(echConfig = fallbackEchConfig)
            }
        }

        /** Tests and production use this ordered failover rule; only usable results stop iteration. */
        fun <T> firstUsableEndpoint(
            endpoints: List<String>,
            isUsable: (T) -> Boolean,
            query: (String) -> T,
            onAttempt: (index: Int, outcome: String) -> Unit = { _, _ -> },
        ): T? {
            endpoints.forEachIndexed { index, endpoint ->
                val result = runCatching { query(endpoint) }.getOrNull()
                if (result == null) {
                    onAttempt(index + 1, "error")
                } else if (isUsable(result)) {
                    onAttempt(index + 1, "usable")
                    return result
                } else {
                    onAttempt(index + 1, "unusable")
                }
            }
            return null
        }

        private fun splitCommaList(value: String): List<String> = value.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)

        private fun normalizeTxtPiece(value: String): String = value.trim()
            .replace("\\\"", "\"")
            .replace("\"", "")

        private fun validatedHttpsEndpoint(value: String): String? {
            val candidate = value.trim()
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
            if (uri.rawUserInfo != null || uri.port == 0 || uri.port > 65535 || uri.fragment != null) return null
            return uri.toASCIIString()
        }

        private fun decodeEchConfig(value: String): ByteArray? {
            val encoded = value.trim().removeSurrounding("\"").takeIf(String::isNotEmpty) ?: return null
            return runCatching { Base64.getDecoder().decode(encoded) }
                .recoverCatching { Base64.getUrlDecoder().decode(encoded) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }
    }
}
