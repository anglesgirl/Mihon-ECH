package eu.kanade.tachiyomi.network

import java.net.InetSocketAddress

/** Optional app-provided local ECH reverse proxy endpoint. */
interface EchProxyProvider {
    val enabled: Boolean

    /** True when the local shared DoH/ECH transport should handle this HTTPS host. */
    fun shouldProxy(host: String): Boolean
    fun start(): InetSocketAddress?
    fun stop()

    /** Applies changed public ECH settings without restarting the application. */
    fun reload(): InetSocketAddress?

    /** Human-readable state from the local proxy, suitable for diagnostics. */
    fun status(): String
}
