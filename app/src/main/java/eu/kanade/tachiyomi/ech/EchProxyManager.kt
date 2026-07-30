package eu.kanade.tachiyomi.ech

import android.content.Context
import echproxy.Echproxy
import java.net.InetSocketAddress
import java.net.ServerSocket
import eu.kanade.tachiyomi.network.EchProxyProvider
import eu.kanade.tachiyomi.network.NetworkPreferences

/**
 * Starts the on-device ECH reverse proxy used by the opt-in network interceptor.
 * Remote settings are intentionally public: only DoH endpoint URLs and Cloudflare
 * AS13335 edge IPs are read from the TXT record; no cookies or credentials leave
 * the app during bootstrap.
 */
class EchProxyManager(
    private val context: Context,
    private val preferences: NetworkPreferences,
) : EchProxyProvider {
    @Volatile private var port: Int? = null

    override val enabled: Boolean
        get() = preferences.echEnabled.get()

    fun isProtectedHost(host: String): Boolean =
        host == "archiveofourown.org" || host == "www.archiveofourown.org"

    override fun start(): InetSocketAddress? {

        port?.let { return InetSocketAddress("127.0.0.1", it) }
        return try {
            val config = fetchRemoteConfig()
            val selectedPort = ServerSocket(0).use { it.localPort }
            Echproxy.start(
                "127.0.0.1:$selectedPort",
                "archiveofourown.org",
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
        try { Echproxy.stop() } catch (_: Throwable) { }
        port = null
    }

    private fun fetchRemoteConfig(): Config {
        val bootstrap = listOf(
            "https://pieqllv9i7.cloudflare-gateway.com/dns-query",
            "https://m2b4x7vw98.cloudflare-gateway.com/dns-query",
            "https://dz1598pphb.cloudflare-gateway.com/dns-query",
        )
        val txt = bootstrap.firstNotNullOfOrNull { doh ->
            try { Echproxy.fetchTxt(doh, "ech-config.anglesgirl.eu.org") } catch (_: Throwable) { null }
        } ?: return Config(bootstrap, "")
        val values = txt.split(';', '\n').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
        }.toMap()
        val dohs = listOfNotNull(values["doh"], values["doh2"], values["doh3"])
            .filter { it.startsWith("https://") }
            .ifEmpty { bootstrap }
        val ips = values["ip"] ?: values["ips"] ?: ""
        return Config(dohs, ips)
    }

    private data class Config(val doh: List<String>, val ips: String)
}
