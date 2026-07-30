package eu.kanade.tachiyomi.ech

import android.content.Context
import echproxy.Echproxy
import eu.kanade.tachiyomi.network.EchProxyProvider
import eu.kanade.tachiyomi.network.NetworkPreferences
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Lifecycle and public configuration bridge for the shared ech-proxy-android
 * AAR. The proxy makes the final per-host choice: AS13335 targets receive ECH
 * (target HTTPS ech= first, then TXT fallback); all other targets use ordinary
 * TLS over DoH-resolved addresses.
 */
class EchProxyManager(
    private val context: Context,
    private val preferences: NetworkPreferences,
) : EchProxyProvider {
    @Volatile private var port: Int? = null
    @Volatile private var activeConfig: Config? = null

    override val enabled: Boolean
        get() = preferences.echEnabled.get()

    override fun shouldProxy(host: String): Boolean {
        if (!enabled || host.isBlank()) return false
        activeConfig ?: runCatching { fetchRemoteConfig() }.getOrNull()?.also { activeConfig = it } ?: return false
        return true
    }

    override fun start(): InetSocketAddress? {
        port?.let { return InetSocketAddress("127.0.0.1", it) }
        return runCatching {
            val config = activeConfig ?: fetchRemoteConfig().also { activeConfig = it }
            val selectedPort = ServerSocket(0).use { it.localPort }
            Echproxy.start(
                "127.0.0.1:$selectedPort",
                "mihon.invalid",
                config.echConfigList,
                config.doh.joinToString(","),
                config.ips,
                context.filesDir.resolve("mihon-ech-public-config.json").absolutePath,
                false,
            )
            InetSocketAddress("127.0.0.1", selectedPort).also { port = selectedPort }
        }.getOrNull()
    }

    @Synchronized
    override fun stop() {
        runCatching { Echproxy.stop() }
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
        val domain = preferences.echConfigDomain.get().trim().trimEnd('.')
        val txt = domain.takeIf { it.isNotEmpty() }?.let { name ->
            bootstrap.firstNotNullOfOrNull { doh -> runCatching { Echproxy.fetchTxt(doh, name) }.getOrNull() }
        } ?: return Config(bootstrap, preferences.echIpList.get().trim(), "")
        val values = txt.split(';', '\n').mapNotNull { item ->
            val separator = item.indexOf('=')
            item.takeIf { separator > 0 }?.let {
                item.substring(0, separator).trim().lowercase() to item.substring(separator + 1).trim()
            }
        }.toMap()
        val dohs = listOfNotNull(values["doh"], values["doh2"], values["doh3"])
            .filter { it.startsWith("https://") }
            .ifEmpty { bootstrap }
        return Config(
            dohs,
            values["ip"] ?: values["ips"] ?: preferences.echIpList.get().trim(),
            values["ech"] ?: values["echconfig"] ?: "",
        )
    }

    private data class Config(val doh: List<String>, val ips: String, val echConfigList: String)
}
