package eu.kanade.tachiyomi.network

import eu.kanade.tachiyomi.network.interceptor.isCloudflareChallenge
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EchRoutingInterceptorTest {

    @Test
    fun `recognized Cloudflare challenge is eligible for safe request retry`() {
        val request = request("GET")
        val response = response(request, code = 403, server = "cloudflare")

        assertTrue(response.isCloudflareChallenge())
        assertTrue(shouldRetryChallengeViaKatHttp3(request, response))
    }

    @Test
    fun `ordinary successful response remains direct`() {
        val request = request("GET")
        val response = response(request, code = 200, server = "cloudflare")

        assertFalse(response.isCloudflareChallenge())
        assertFalse(shouldRetryChallengeViaKatHttp3(request, response))
    }

    @Test
    fun `ordinary authorization and not found responses remain direct`() {
        listOf(401, 404).forEach { status ->
            val request = request("GET")
            val response = response(request, code = status, server = "cloudflare")

            assertFalse(response.isCloudflareChallenge())
            assertFalse(shouldRetryChallengeViaKatHttp3(request, response))
        }
    }

    @Test
    fun `Cloudflare error without challenge markers is not retried`() {
        val request = request("GET")
        val response = response(
            request,
            code = 403,
            server = "cloudflare",
            body = "<html><body>Access denied</body></html>",
        )

        assertFalse(shouldRetryChallengeViaKatHttp3(request, response))
    }

    @Test
    fun `non replayable methods are not retried even when challenge is detected`() {
        val request = request("POST", body = "payload")
        val response = response(request, code = 403, server = "cloudflare")

        assertTrue(response.isCloudflareChallenge())
        assertFalse(shouldRetryChallengeViaKatHttp3(request, response))
    }

    @Test
    fun `only bodyless GET HEAD and OPTIONS requests are safe for cached H3 routing`() {
        listOf("GET", "HEAD", "OPTIONS").forEach { method ->
            assertTrue(request(method).isSafeForKatHttp3Retry())
        }
        assertFalse(request("GET", body = "payload").isSafeForKatHttp3Retry())
        listOf(request("POST", body = "payload"), request("PUT", body = "payload"), request("DELETE")).forEach {
            assertFalse(it.isSafeForKatHttp3Retry())
        }
    }

    @Test
    fun `non Cloudflare server errors do not trigger ECH retry`() {
        val request = request("GET")
        val response = response(request, code = 503, server = "origin")

        assertFalse(shouldRetryChallengeViaKatHttp3(request, response))
    }

    private fun request(method: String, body: String? = null): Request =
        Request.Builder()
            .url("https://example.org/chapter")
            .apply {
                when (method) {
                    "POST" -> post(requireNotNull(body).toRequestBody())
                    "HEAD" -> head()
                    "DELETE" -> delete()
                    else -> this.method(method, body?.toRequestBody())
                }
            }
            .build()

    private fun response(
        request: Request,
        code: Int,
        server: String,
        body: String = CHALLENGE_BODY,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .header("Server", server)
        .body(body.toResponseBody())
        .build()

    private companion object {
        const val CHALLENGE_BODY = """
            <html><body><div id="challenge-error-title">Please wait</div></body></html>
        """
    }
}
