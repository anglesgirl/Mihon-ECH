package eu.kanade.tachiyomi.network

import java.net.InetSocketAddress

/** Optional app-provided local ECH reverse proxy endpoint. */
interface EchProxyProvider {
    val enabled: Boolean
    fun start(): InetSocketAddress?
    fun stop()
}
