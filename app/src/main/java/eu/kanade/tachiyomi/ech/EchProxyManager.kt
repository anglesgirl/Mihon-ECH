package eu.kanade.tachiyomi.ech

import android.content.Context
import dev.kathttp3.KatHttp3Client
import dev.kathttp3.KatHttp3ClientConfig
import eu.kanade.tachiyomi.network.CfEchResolver
import eu.kanade.tachiyomi.network.ChannelDecision
import eu.kanade.tachiyomi.network.EchProxyProvider
import eu.kanade.tachiyomi.network.NetworkPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * kathttp3 原生 ECH/H3 传输管理（替换原 ech-proxy-go 本地代理）。
 *
 * - 持有 KatHttp3Client 单例：DoH 解析 + 目标域名 ech / CF 官方活值兜底。
 * - 通道判定缓存（域名 → DIRECT / KAT_HTTP3），失败驱动，进程内存态。
 * - 保留 EchProxyProvider 接口语义与类名，App/DI/设置页无需改动。
 */
class EchProxyManager(
    private val context: Context,
    private val preferences: NetworkPreferences,
) : EchProxyProvider {

    @Volatile private var client: KatHttp3Client? = null

    @Volatile private var diagnostics: EchDiagnostics? = null

    /** 域名 → 通道判定（进程内存；后续可持久化 + 云端共享）。 */
    private val decisions = ConcurrentHashMap<String, ChannelDecision>()

    override val enabled: Boolean
        get() = preferences.echEnabled.get()

    fun setDiagnostics(value: EchDiagnostics) {
        diagnostics = value
    }

    fun startAsync() {
        diagnostics?.event("ech_start_requested", "enabled=$enabled")
        if (enabled) {
            runCatching { ensureClient() }
                .onFailure {
                    diagnostics?.event("ech_init_failed", "error=${it.javaClass.simpleName}: ${it.message}")
                    logcat(LogPriority.ERROR, it) { "ECH: kathttp3 init failed" }
                }
        }
    }

    private fun ensureClient(): KatHttp3Client? = synchronized(this) {
        client ?: runCatching {
            val dohEndpoints = preferences.echDohEndpoints.get()
                .split(',')
                .map(String::trim)
                .filter { it.startsWith("https://") }
            if (dohEndpoints.isEmpty()) {
                diagnostics?.event("ech_init_failed", "reason=no_doh_endpoints")
                logcat(LogPriority.WARN) { "ECH: no DoH endpoints configured" }
                return@synchronized null
            }
            val resolver = CfEchResolver(dohEndpoints)
            KatHttp3Client(
                config = KatHttp3ClientConfig(
                    resolver = resolver,
                    connectTimeoutMillis = 8_000,
                    handshakeTimeoutMillis = 8_000,
                    readTimeoutMillis = 30_000,
                    callTimeoutMillis = 60_000,
                    followRedirects = true,
                    maxRedirects = 5,
                ),
                applicationContext = context.applicationContext,
            ).also { client = it }
        }.onFailure {
            diagnostics?.event("ech_init_failed", "error=${it.javaClass.simpleName}: ${it.message}")
            logcat(LogPriority.ERROR, it) { "ECH: kathttp3 init failed" }
        }.getOrNull()
    }

    // ---- EchProxyProvider ----

    override fun shouldProxy(host: String): Boolean = enabled && host.isNotBlank()

    /** 旧方案（Go 本地代理端口）已废弃：返回 null，路由改为拦截器直调 kathttp3。 */
    override fun start(): InetSocketAddress? = null

    override fun stop() {
        synchronized(this) {
            runCatching { client?.close() }
            client = null
            decisions.clear()
        }
    }

    override fun reload(): InetSocketAddress? {
        stop()
        return null
    }

    override fun status(): String =
        if (client != null) "kathttp3 active" else "kathttp3 idle"

    override fun diagnostic(name: String, detail: String) {
        diagnostics?.event(name, detail)
    }

    override fun katHttp3(): KatHttp3Client? = if (enabled) ensureClient() else null

    override fun decision(host: String): ChannelDecision =
        decisions.getOrDefault(host, ChannelDecision.UNKNOWN)

    override fun recordDecision(host: String, decision: ChannelDecision) {
        decisions[host] = decision
    }

    override fun recordFailure(host: String) {
        // 探测失败 → 后续直接走 kathttp3，不再反复 5s 探测
        decisions[host] = ChannelDecision.KAT_HTTP3
    }
}
