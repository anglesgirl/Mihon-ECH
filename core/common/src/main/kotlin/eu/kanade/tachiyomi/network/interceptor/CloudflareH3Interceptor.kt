package eu.kanade.tachiyomi.network.interceptor

import com.anglesgirl.echsdk.EchDoh
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.EchH3Diag
import eu.kanade.tachiyomi.network.KatHttp3State
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * H3 优先处理无登录态的 Cloudflare GET/HEAD；失败沿用 OkHttp。
 * 并发信号量限制同时 in-flight 的 H3 握手数，避免 CF 边缘对
 * 同一 IP 大量并发 QUIC 握手限流（表现为 Handshake timed out / TLS handshake error）。
 */
class CloudflareH3Interceptor(
    private val cookieJar: AndroidCookieJar,
) : Interceptor {

    private val h3Semaphore = Semaphore(4)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!shouldUseH3(request)) {
            EchH3Diag.log("ECH/H3: skip url=${request.url} method=${request.method}")
            return chain.proceed(request)
        }

        // 并发握手已满（2 秒内无空闲许可）→ 直接走 OkHttp，避免在 H3 队列里干等超时
        if (!h3Semaphore.tryAcquire(2, TimeUnit.SECONDS)) {
            EchH3Diag.log("ECH/H3: busy fallback url=${request.url}")
            return chain.proceed(request)
        }
        try {
            return tryIntercept(chain, request)
        } finally {
            h3Semaphore.release()
        }
    }

    private fun tryIntercept(chain: Interceptor.Chain, request: Request): Response {
        val requestHeaders = request.headers.toMultimap().flatMap { (name, values) ->
            values.map { name to it }
        }.toMutableList()
        val cookies = cookieJar.get(request.url)
        val cookieHeader = cookies.joinToString("; ") { it.toString() }
        if (cookies.isNotEmpty()) {
            requestHeaders += "cookie" to cookieHeader
        }
        EchH3Diag.log(
            "ECH/H3: send url=${request.url} method=${request.method} " +
                "cookies=${cookies.size} " +
                "cf_clearance=${cookieHeader.contains("cf_clearance=")} " +
                "__cf_bm=${cookieHeader.contains("__cf_bm=")}",
        )

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
            EchH3Diag.log(
                "ECH/H3: transport fail url=${request.url} method=${request.method} " +
                    "err=${error.javaClass.simpleName}: ${error.message}",
            )
            if (request.method == "GET" || request.method == "HEAD") return chain.proceed(request)
            throw IOException("H3 ${request.method} 传输失败，不自动重发以避免重复提交", error)
        }

        val responseHeaders = Headers.Builder().apply {
            h3Response.headers.forEach { add(it.name, it.value) }
        }.build()
        cookieJar.saveSetCookieHeaders(request.url, responseHeaders.values("set-cookie"))

        val status = h3Response.status
        EchH3Diag.log(
            "ECH/H3: response url=${request.url} status=$status " +
                "server=${responseHeaders["server"] ?: "-"} " +
                "cf-ray=${responseHeaders["cf-ray"] ?: "-"}",
        )
        if (status == 403 || status == 400) {
            // 403 body 能区分 CF 拦截类型：
            // 空 body/纯文本 = BIC 直接拒绝（TLS 指纹不符，不给挑战）；
            // 含 turnstile/js challenge = CF 有下发验证页，但客户端拿不到/没渲染。
            val bodySnippet = h3Response.body.decodeToString().take(1000)
            EchH3Diag.log("ECH/H3: $status body=$bodySnippet")
        }

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_3)
            .code(status)
            .message("HTTP $status")
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
        val host = request.url.host
        // Cloudflare（走 ECH）+ GitHub/Fastly/CDN 系列（明文 H3）：
        // 这些 CDN 都支持 HTTP/3，且明文 H3 走 UDP 不受 TCP RST 阻断
        val isCloudflare = EchDoh.isCloudflareHost(host)
        val isH3Cdn = host == "github.com" ||
            host == "raw.githubusercontent.com" ||
            host.endsWith("githubusercontent.com") ||
            host.endsWith(".github.io") ||
            host.endsWith("githubassets.com") ||
            host.endsWith(".jsdelivr.net") ||
            host.endsWith(".fastly.net")
        if (!isCloudflare && !isH3Cdn) return false
        val body = request.body ?: return request.method != "POST"
        return body.contentLength() in 0..(2L * 1024 * 1024)
    }
}
