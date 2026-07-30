package eu.kanade.tachiyomi.ech

import android.content.Context
import echproxy.Echproxy
import eu.kanade.tachiyomi.network.EchProxyProvider
import eu.kanade.tachiyomi.network.NetworkPreferences
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Starts the on-device ECH reverse proxy used by the opt-in network interceptor.
 * Its bootstrap domain, DoH failover endpoints, and optional edge IP list are
 * user-configurable. The TXT record can still supply refreshed public endpoints
 * and IPs; no cookies or credentials leave the app during bootstrap.
 */
class EchProxyManager(
    private val context: Context,
    private val preferences: NetworkPreferences,
) : EchProxyProvider {
    @Volatile private var port: Int? = null
    @Volatile private var activeConfig: Config? = null

    override val enabled: Boolean
        get() = preferences.echEnabled.get()

    /**
     * Route every valid HTTPS host through the local DoH transport while the
     * feature is enabled. The Go module then chooses per host: AS13335 plus a
     * host-owned HTTPS ech= record gets ECH; every other host gets ordinary TLS
     * over its DoH-resolved addresses.
     */
    override fun shouldProxy(host: String): Boolean {
        if (!enabled || host.isBlank()) return false
        val config = activeConfig ?: runCatching { fetchRemoteConfig() }.getOrNull() ?: return false
        activeConfig = config
        return true
    }

    override fun start(): InetSocketAddress? {
        port?.let { return InetSocketAddress("127.0.0.1", it) }
        return try {
            val config = activeConfig ?: fetchRemoteConfig().also { activeConfig = it }
            val selectedPort = ServerSocket(0).use { it.localPort }
            Echproxy.start(
                "127.0.0.1:$selectedPort",
                "mihon.invalid",
                "",
                config.doh.joinToString(","),
                config.ips,
                context.filesDir.resolve("mihon-ech-public-config.json").absolutePath,
                false,
            )
            selectedPort.also { port = it }.let { InetSocketAddress("127.0.0.1", it) }
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    override fun stop() {
        try {
            Echproxy.stop()
        } catch (_: Throwable) { }
        port = null
        activeConfig = null
    }

    private fun fetchRemoteConfig(): Config {
        val configuredDoh = preferences.echDohEndpoints.get()
            .split(',')
            .map(String::trim)
            .filter { it.startsWith("https://") }
        val bootstrap = configuredDoh.ifEmpty {
            listOf(
                "https://pieqllv9i7.cloudflare-gateway.com/dns-query",
                "https://m2b4x7vw98.cloudflare-gateway.com/dns-query",
                "https://dz1598pphb.cloudflare-gateway.com/dns-query",
            )
        }
        val configDomain = preferences.echConfigDomain.get().trim().trimEnd('.')
        val txt = configDomain.takeIf { it.isNotBlank() }?.let { domain ->
            bootstrap.firstNotNullOfOrNull { doh ->
                try {
                    Echproxy.fetchTxt(doh, domain)
                } catch (_: Throwable) {
                    null
                }
            }
        } ?: return Config(bootstrap, preferences.echIpList.get().trim())
        val values = txt.split(';', '\n').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
        }.toMap()
        val dohs = listOfNotNull(values["doh"], values["doh2"], values["doh3"])
            .filter { it.startsWith("https://") }
            .ifEmpty { bootstrap }
        val ips = values["ip"] ?: values["ips"] ?: preferences.echIpList.get().trim()
        return Config(dohs, ips)
    }

    private data class Config(val doh: List<String>, val ips: String)
}
