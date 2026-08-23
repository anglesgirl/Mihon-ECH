package eu.kanade.tachiyomi.ech

import android.content.Context
import echproxy.Echproxy
import eu.kanade.tachiyomi.network.EchProxyProvider
import eu.kanade.tachiyomi.network.NetworkPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * Lifecycle and public configuration bridge for the shared ech-proxy-go
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
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var diagnostics: EchDiagnostics? = null

    fun setDiagnostics(value: EchDiagnostics) {
        diagnostics = value
    }

    override val enabled: Boolean
        get() = preferences.echEnabled.get()

    fun startAsync() {
        diagnostics?.event("proxy_start_requested", "enabled=$enabled")
        if (enabled) executor.execute { start() }
    }

    override fun shouldProxy(host: String): Boolean {
        if (!enabled || host.isBlank()) return false
        activeConfig ?: runCatching { fetchRemoteConfig() }
            .onFailure { logcat(LogPriority.ERROR, it) { "ECH: could not load public configuration" } }
            .getOrNull()
            ?.also { activeConfig = it }
            ?: return false
        return true
    }

    override fun start(): InetSocketAddress? {
        port?.let { return InetSocketAddress("127.0.0.1", it) }
        return runCatching {
            val config = activeConfig ?: fetchRemoteConfig().also { activeConfig = it }
            diagnostics?.event(
                "proxy_config",
                "doh_count=${config.doh.size} ip_count=${config.ips.split(',').count {
                    it.isNotBlank()
                }} ech_config=${config.echConfigList.isNotBlank()}",
            )
            val selectedPort = ServerSocket(0).use { it.localPort }
            Echproxy.start(
                "127.0.0.1:$selectedPort",
                config.doh.joinToString(","),
                context.filesDir.resolve("mihon-ech-public-config.json").absolutePath,
                true,
            )
            logcat(LogPriority.INFO) { "ECH: local proxy started on 127.0.0.1:$selectedPort" }
            diagnostics?.event("proxy_started", "status=${Echproxy.lastStatus()} port=$selectedPort")
            InetSocketAddress("127.0.0.1", selectedPort).also { port = selectedPort }
        }.onFailure {
            diagnostics?.event("proxy_start_failed", "error=${it.javaClass.simpleName}: ${it.message}")
            logcat(LogPriority.ERROR, it) { "ECH: local proxy failed to start" }
        }.getOrNull()
    }

    @Synchronized
    override fun stop() {
        runCatching { Echproxy.stop() }
        port = null
        activeConfig = null
    }

    @Synchronized
    override fun reload(): InetSocketAddress? {
        stop()
        return if (enabled) start() else null
    }

    override fun status(): String = runCatching { Echproxy.lastStatus() }
        .getOrDefault("ECH proxy is not running")

    private fun fetchRemoteConfig(): Config {
        val domain = preferences.echConfigDomain.get().trim().trimEnd('.')
        val txt = domain.takeIf { it.isNotEmpty() }
            ?.let { name -> runCatching { Echproxy.fetchBootstrapTxt(name) }.getOrNull() }
            ?: throw IllegalStateException("ECH bootstrap TXT unavailable")
        val values = txt.split(';', '\n').mapNotNull { item ->
            val separator = item.indexOf('=')
            item.takeIf { separator > 0 }?.let {
                item.substring(0, separator).trim().lowercase() to item.substring(separator + 1).trim()
            }
        }.toMap()
        val dohs = listOfNotNull(values["doh"], values["doh2"], values["doh3"])
            .flatMap { it.split(',') }
            .map(String::trim)
            .filter { it.startsWith("https://") }
        if (dohs.isEmpty()) throw IllegalStateException("ECH bootstrap TXT has no DoH endpoints")
        return Config(
            dohs,
            values["ip"] ?: values["ips"] ?: preferences.echIpList.get().trim(),
            values["ech"] ?: values["echconfig"] ?: "",
        )
    }

    private data class Config(val doh: List<String>, val ips: String, val echConfigList: String)
}
