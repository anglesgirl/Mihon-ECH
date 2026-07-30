package eu.kanade.tachiyomi.network

import okhttp3.Interceptor
import okhttp3.Response

/** Routes only AO3 through the local ECH reverse proxy when it is enabled. */
class EchRoutingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val provider = EchProxyRegistry.provider
        val request = chain.request()
        val host = request.url.host
        if (provider == null || !provider.enabled ||
            (host != "archiveofourown.org" && host != "www.archiveofourown.org")
        ) {
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
