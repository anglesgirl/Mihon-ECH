package eu.kanade.tachiyomi.network

import java.net.InetSocketAddress

/** 通道判定结果。 */
enum class ChannelDecision {
    /** 未判定（首次遇到，需要快速探测）。 */
    UNKNOWN,

    /** 已知可直连，放行 OkHttp。 */
    DIRECT,

    /** 已知需走 kathttp3（ECH/H3 传输）。 */
    KAT_HTTP3,
}

/** Optional app-provided ECH/H3 transport. */
interface EchProxyProvider {
    val enabled: Boolean

    /** True when the shared transport should handle this HTTPS host. */
    fun shouldProxy(host: String): Boolean

    /** @deprecated Go 本地代理端口（旧方案）。kathttp3 版本返回 null。 */
    fun start(): InetSocketAddress?
    fun stop()

    /** Applies changed public ECH settings without restarting the application. */
    fun reload(): InetSocketAddress?

    /** Human-readable state, suitable for diagnostics. */
    fun status(): String

    /** Records a bounded, host-scoped diagnostic event in the app layer. */
    fun diagnostic(name: String, detail: String = "") {}

    /** kathttp3 客户端（原生 ECH/H3 传输）。返回 null 时请求放行 OkHttp。 */
    fun katHttp3(): dev.kathttp3.KatHttp3Client? = null

    /** 通道判定缓存：域名 → 通道。 */
    fun decision(host: String): ChannelDecision = ChannelDecision.UNKNOWN

    /** 记录一次判定结果。 */
    fun recordDecision(host: String, decision: ChannelDecision) {}

    /** 记录一次探测失败（后续直接走 kathttp3，不再反复 5s 探测）。 */
    fun recordFailure(host: String) {}
}
