package eu.kanade.tachiyomi.network.interceptor

import com.anglesgirl.echsdk.EchDoh
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.KatHttp3State
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/** H3优先处理无登录态的 Cloudflare GET/HEAD；失败沿用 OkHttp。 */
class CloudflareH3Interceptor(
    private val cookieJar: AndroidCookieJar,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!shouldUseH3(request)) return chain.proceed(request)

        val requestHeaders = request.headers.toMultimap().flatMap { (name, values) ->
            values.map { name to it }
        }.toMutableList()
        val cookies = cookieJar.get(request.url)
        if (cookies.isNotEmpty()) {
            requestHeaders += "cookie" to cookies.joinToString("; ") { it.toString() }
        }
        val body = request.body?.let { requestBody ->
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            buffer.readByteArray()
        }
        val h3Response = try {
            runBlocking {
                KatHttp3State.execute(
                    method = request.method,
                    url = request.url.toString(),
                    headers = requestHeaders,
                    body = body,
                )
            }
        } catch (error: Exception) {
            if (request.method == "GET" || request.method == "HEAD") return chain.proceed(request)
            throw IOException("H3 ${request.method} 传输失败，不自动重发以避免重复提交", error)
        }

        val responseHeaders = Headers.Builder().apply {
            h3Response.headers.forEach { add(it.name, it.value) }
        }.build()
        cookieJar.saveSetCookieHeaders(request.url, responseHeaders.values("set-cookie"))

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_3)
            .code(h3Response.status)
            .message("HTTP ${h3Response.status}")
            .headers(responseHeaders)
            .body(
                h3Response.body.toResponseBody(
                    responseHeaders["content-type"]?.toMediaTypeOrNull(),
                ),
            )
            .build()
    }

    private fun shouldUseH3(request: Request): Boolean {
        if (request.method !in setOf("GET", "HEAD", "POST")) return false
        if (request.header("Authorization") != null) return false
        if (request.header("Range") != null) return false
        if (request.url.scheme != "https") return false
        if (!EchDoh.isCloudflareHost(request.url.host)) return false
        val body = request.body ?: return request.method != "POST"
        return body.contentLength() in 0..(2L * 1024 * 1024)
    }
}
