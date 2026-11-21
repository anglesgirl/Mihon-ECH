package eu.kanade.tachiyomi.network

import dev.kathttp3.DnsResolver
import dev.kathttp3.DohResolver
import dev.kathttp3.ResolvedAddress
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** HTTPS DoH resolver with historical TXT bootstrap and Cloudflare live ECH fallback. */
class CfEchResolver(
    dohEndpoints: List<String>,
    private val configDomain: String = "",
    configuredEdgeIps: List<String> = emptyList(),
    private val officialEchConfigDomain: String = "cloudflare-ech.com",
    private val refreshIntervalMs: Long = 6 * 60 * 60 * 1000L,
    private val diagnostic: (name: String, detail: String) -> Unit = { _, _ -> },
) : DnsResolver {
    private val configuredEndpoints = EchResolverBootstrap.parseEndpointList(dohEndpoints.joinToString(","))
    private val configuredIps = configuredEdgeIps.filter { EchResolverBootstrap.isLiteralIp(it) }.distinct()
    private val dohResolvers = ConcurrentHashMap<String, DohResolver>()

    @Volatile private var bootstrap: EchResolverBootstrap? = null

    /** Cached official ECH values are fetched with the same verified HTTPS DoH transport. */
    private val officialEch = AtomicReference<ByteArray?>(null)
    private val lastRefresh = AtomicReference(0L)

    override fun resolve(host: String, port: Int): List<ResolvedAddress> {
        val config = loadBootstrap()
        val dnsAddresses = resolveWithEndpoints(
            endpoints = config.dohEndpoints,
            host = host,
            port = port,
            source = "target",
            isUsable = { addresses ->
                addresses.any { address ->
                    EchResolverBootstrap.isLiteralIp(address.ip) || address.echConfig != null
                }
            },
        ).orEmpty()

        // HTTPS RR ECH wins; TXT ECH and then Cloudflare live ECH are fallback sources only.
        val dnsEch = dnsAddresses.firstNotNullOfOrNull { it.echConfig }
        val fallbackEch = dnsEch ?: config.echConfig ?: refreshOfficialEch(config.dohEndpoints)
        return EchResolverBootstrap.addressesWithFallback(
            dnsAddresses = dnsAddresses,
            edgeIps = config.edgeIps,
            port = port,
            fallbackEchConfig = fallbackEch,
        )
    }

    private fun loadBootstrap(): EchResolverBootstrap {
        bootstrap?.let { return it }
        synchronized(this) {
            bootstrap?.let { return it }
            val settingsFallback = EchResolverBootstrap.parseTxt(
                pieces = emptyList(),
                configuredEndpoints = configuredEndpoints,
                configuredIps = configuredIps,
            )
            val txtPieces = mutableListOf<String>()
            val domain = configDomain.trim().trimEnd('.')
            if (domain.isNotEmpty()) {
                configuredEndpoints.forEachIndexed { index, endpoint ->
                    val pieces = runCatching { fetchTxt(endpoint, domain) }.getOrNull()
                    val outcome = if (pieces.isNullOrEmpty()) "unusable" else "usable"
                    diagnostic(
                        "ech_bootstrap_attempt",
                        "source=txt count=${configuredEndpoints.size} index=${index + 1} outcome=$outcome",
                    )
                    if (!pieces.isNullOrEmpty()) {
                        txtPieces += pieces
                        val parsed = EchResolverBootstrap.parseTxt(txtPieces, configuredEndpoints, configuredIps)
                        if (parsed.txtDohEndpoints.isNotEmpty()) {
                            bootstrap = parsed
                            diagnostic(
                                "ech_bootstrap_loaded",
                                "source=txt count=${parsed.dohEndpoints.size} index=${index + 1} outcome=usable",
                            )
                            return parsed
                        }
                    }
                }
            }
            val selected = if (txtPieces.isEmpty()) {
                settingsFallback
            } else {
                EchResolverBootstrap.parseTxt(txtPieces, configuredEndpoints, configuredIps)
            }
            bootstrap = selected
            val source = if (txtPieces.isEmpty()) "settings" else "txt"
            val outcome = if (selected.dohEndpoints.isEmpty()) "unusable" else "usable"
            diagnostic(
                "ech_bootstrap_loaded",
                "source=$source count=${selected.dohEndpoints.size} index=0 outcome=$outcome",
            )
            return selected
        }
    }

    private fun fetchTxt(endpoint: String, domain: String): List<String> {
        val separator = when {
            endpoint.endsWith('?') || endpoint.endsWith('&') -> ""
            '?' in endpoint -> "&"
            else -> "?"
        }
        val url = URL(
            "$endpoint${separator}name=${URLEncoder.encode(domain, StandardCharsets.UTF_8.name())}" +
                "&type=TXT",
        )
        val connection = url.openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("HTTPS endpoint required")
        return try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/dns-json")
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (connection.responseCode != 200) return emptyList()
            val body = connection.inputStream.use(::readBounded)
            val answers = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            buildList {
                for (index in 0 until answers.length()) {
                    val answer = answers.optJSONObject(index) ?: continue
                    if (answer.optInt("type") == TXT_RECORD_TYPE) {
                        answer.optString("data").takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_TXT_RESPONSE_BYTES) {
                throw IllegalArgumentException("TXT response too large")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun resolveWithEndpoints(
        endpoints: List<String>,
        host: String,
        port: Int,
        source: String,
        isUsable: (List<ResolvedAddress>) -> Boolean,
    ): List<ResolvedAddress>? = EchResolverBootstrap.firstUsableEndpoint(
        endpoints = endpoints,
        isUsable = isUsable,
        query = { endpoint -> dohResolvers.getOrPut(endpoint) { DohResolver(endpoint) }.resolve(host, port) },
        onAttempt = { index, outcome ->
            diagnostic("ech_doh_attempt", "source=$source count=${endpoints.size} index=$index outcome=$outcome")
        },
    )

    private fun refreshOfficialEch(endpoints: List<String>): ByteArray? {
        val now = System.currentTimeMillis()
        if (now - lastRefresh.get() < refreshIntervalMs) return officialEch.get()
        synchronized(this) {
            if (now - lastRefresh.get() < refreshIntervalMs) return officialEch.get()
            val live = resolveWithEndpoints(
                endpoints = endpoints,
                host = officialEchConfigDomain,
                port = 443,
                source = "official",
                isUsable = { addresses -> addresses.any { it.echConfig != null } },
            )?.firstNotNullOfOrNull { it.echConfig }
            if (live != null) officialEch.set(live)
            lastRefresh.set(now)
            return officialEch.get()
        }
    }

    private fun diagnostic(name: String, detail: String) {
        runCatching { diagnostic.invoke(name, detail) }
    }

    private companion object {
        const val TXT_RECORD_TYPE = 16
        const val MAX_TXT_RESPONSE_BYTES = 256 * 1024
    }
}
