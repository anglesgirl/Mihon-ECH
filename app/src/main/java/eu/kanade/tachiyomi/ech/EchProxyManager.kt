package eu.kanade.tachiyomi.ech

import android.content.Context
import echproxy.Echproxy
import eu.kanade.tachiyomi.network.EchProxyProvider
import eu.kanade.tachiyomi.network.NetworkPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
            .onFailure {
                diagnostic("bootstrap_failed", "host=$host error=${it.javaClass.simpleName}")
                logcat(LogPriority.ERROR, it) { "ECH: could not load public configuration" }
            }
            .getOrElse { throw java.io.IOException("ECH public configuration unavailable", it) }
            .also { activeConfig = it }
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
            awaitListener(selectedPort)
            check(Echproxy.isRunning()) { "Go proxy stopped during startup" }
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

    override fun diagnostic(name: String, detail: String) {
        diagnostics?.event(name, detail)
        if (name.endsWith("_failed") || name.endsWith("_refused")) {
            diagnostics?.uploadNow()
        }
    }

    private fun fetchRemoteConfig(): Config {
        val domain = preferences.echConfigDomain.get().trim().trimEnd('.')
        val txt = domain.takeIf { it.isNotEmpty() }
            ?.let { name -> runCatching { Echproxy.fetchBootstrapTxt(name) }.getOrNull() }
            ?: run {
                diagnostic("bootstrap_failed", "domain_configured=${domain.isNotEmpty()}")
                throw IllegalStateException("ECH bootstrap TXT unavailable")
            }
        val values = txt.split(';', '\n').mapNotNull { item ->
            val separator = item.indexOf('=')
            item.takeIf { separator > 0 }?.let {
                item.substring(0, separator).trim().lowercase() to item.substring(separator + 1).trim()
            }
        }.toMap()
        val txtDohs = listOfNotNull(values["doh"], values["doh2"], values["doh3"])
            .flatMap { it.split(',') }
            .map(String::trim)
            .filter { it.startsWith("https://") }
        val configuredDohs = preferences.echDohEndpoints.get()
            .split(',')
            .map(String::trim)
            .filter { it.startsWith("https://") }
        // TXT is the live configuration source. Some bootstrap resolvers return
        // a partial multi-string TXT response, however, so retain the user's
        // validated ECH DoH endpoints as a fail-closed bootstrap fallback.
        val dohs = txtDohs.ifEmpty { configuredDohs }
        if (dohs.isEmpty()) {
            diagnostic("bootstrap_failed", "reason=no_doh_endpoints")
            throw IllegalStateException("ECH bootstrap has no usable DoH endpoints")
        }
        diagnostics?.event(
            "bootstrap_loaded",
            "source=${if (txtDohs.isEmpty()) "settings" else "txt"} doh_count=${dohs.size}",
        )
        return Config(
            dohs,
            values["ip"] ?: values["ips"] ?: preferences.echIpList.get().trim(),
            values["ech"] ?: values["echconfig"] ?: "",
        )
    }

    private fun awaitListener(selectedPort: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var lastError: Throwable? = null
        while (System.nanoTime() < deadline) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", selectedPort), 250)
                }
            }.onSuccess { return }.onFailure { lastError = it }
            Thread.sleep(100)
        }
        throw java.io.IOException("ECH local listener not ready", lastError)
    }

    private data class Config(val doh: List<String>, val ips: String, val echConfigList: String)
}
