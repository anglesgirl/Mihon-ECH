package eu.kanade.tachiyomi.network

import logcat.LogPriority
import okhttp3.Interceptor
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat

/**
 * Routes opted-in HTTPS requests through the shared local DoH/ECH transport.
 * It applies ECH only to AS13335 targets; other targets keep ordinary TLS but
 * are connected using the proxy's DoH-resolved addresses.
 */
class EchRoutingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val provider = EchProxyRegistry.provider
        val request = chain.request()
        val host = request.url.host
        if (provider == null || !provider.enabled || request.url.scheme != "https" || !provider.shouldProxy(host)) {
            return chain.proceed(request)
        }

        logcat(LogPriority.INFO) { "ECH: request intercepted host=$host" }
        val endpoint = provider.start() ?: run {
            val message = "ECH proxy unavailable for $host"
            provider.diagnostic("proxy_start_failed", "host=$host")
            logcat(LogPriority.ERROR) { "ECH: $message; refusing direct TLS" }
            throw java.io.IOException(message)
        }
        logcat(LogPriority.INFO) {
            "ECH: routing $host through local DoH proxy endpoint=${endpoint.hostString}:${endpoint.port}"
        }
        val rewritten = request.newBuilder()
            .url(
                request.url.newBuilder()
                    .scheme("http")
                    .host(endpoint.hostString)
                    .port(endpoint.port)
                    .build(),
            )
            .header("X-Ech-Target", host)
            .build()
        return runCatching { chain.proceed(rewritten) }.onFailure {
            provider.diagnostic(
                "request_failed",
                "host=$host error=${it.javaClass.simpleName}: ${it.message}",
            )
        }.getOrThrow()
    }
}
