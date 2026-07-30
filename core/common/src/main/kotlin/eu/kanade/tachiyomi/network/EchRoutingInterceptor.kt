package eu.kanade.tachiyomi.network

import okhttp3.Interceptor
import okhttp3.Response

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

        val endpoint = provider.start() ?: return chain.proceed(request)
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
        return chain.proceed(rewritten)
    }
}
