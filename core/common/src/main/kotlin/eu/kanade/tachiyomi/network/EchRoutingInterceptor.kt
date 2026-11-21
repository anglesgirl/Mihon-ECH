package eu.kanade.tachiyomi.network

import dev.kathttp3.KatHttp3Client
import dev.kathttp3.KatHttp3Header
import dev.kathttp3.KatHttp3Request
import dev.kathttp3.KatHttp3StreamEvent
import eu.kanade.tachiyomi.network.interceptor.isCloudflareChallenge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val MAX_QUEUED_BODY_CHUNKS = 8
private const val BODY_QUEUE_OFFER_TIMEOUT_MS = 100L

/**
 * Failure/challenge-driven ECH/H3 routing.
 *
 * Ordinary HTTPS responses stay on OkHttp. A request is sent through kathttp3
 * only after a direct connection failure or a Cloudflare challenge response,
 * and only when it is safe to retry. In particular, requests with bodies are
 * never replayed because this adapter does not copy OkHttp bodies to kathttp3.
 */
class EchRoutingInterceptor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : Interceptor {
    private val nextDiagnosticId = AtomicLong(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.scheme != "https") return chain.proceed(request)
        val host = request.url.host
        val provider = EchProxyRegistry.provider
        val k3 = provider?.katHttp3() ?: return chain.proceed(request)

        when (provider.decision(host)) {
            ChannelDecision.KAT_HTTP3 -> {
                if (request.isSafeForKatHttp3Retry()) {
                    val diagnosticId = nextDiagnosticId.incrementAndGet()
                    provider.diagnostic(
                        "ech_request_attempt",
                        "id=$diagnosticId host=$host method=${request.method} reason=cached_kathttp3_route",
                    )
                    return try {
                        viaKatHttp3(k3, request, host, diagnosticId)
                    } catch (error: IOException) {
                        provider.recordDecision(host, ChannelDecision.UNKNOWN)
                        provider.diagnostic(
                            "ech_request_failed",
                            "id=$diagnosticId host=$host " +
                                "reason=cached_kathttp3_io_error type=${error.javaClass.simpleName}",
                        )
                        val directResponse = chain.proceed(request)
                        provider.recordDecision(
                            host,
                            if (directResponse.isCloudflareChallenge()) {
                                ChannelDecision.UNKNOWN
                            } else {
                                ChannelDecision.DIRECT
                            },
                        )
                        provider.diagnostic(
                            "ech_route_fallback_direct",
                            "id=$diagnosticId host=$host method=${request.method} " +
                                "status=${directResponse.code} reason=cached_kathttp3_failure",
                        )
                        directResponse
                    }
                }
                provider.diagnostic(
                    "ech_request_skipped",
                    "host=$host method=${request.method} reason=non_replayable_request",
                )
                return chain.proceed(request)
            }
            ChannelDecision.DIRECT -> return directOrRetry(
                chain = chain,
                request = request,
                host = host,
                provider = provider,
                k3 = k3,
                useShortConnectTimeout = false,
            )
            ChannelDecision.UNKNOWN -> return directOrRetry(
                chain = chain,
                request = request,
                host = host,
                provider = provider,
                k3 = k3,
                useShortConnectTimeout = true,
            )
        }
    }

    private fun directOrRetry(
        chain: Interceptor.Chain,
        request: Request,
        host: String,
        provider: EchProxyProvider,
        k3: KatHttp3Client,
        useShortConnectTimeout: Boolean,
    ): Response {
        val directResponse = try {
            if (useShortConnectTimeout) {
                chain.withConnectTimeout(5, TimeUnit.SECONDS).proceed(request)
            } else {
                chain.proceed(request)
            }
        } catch (error: IOException) {
            if (!request.isSafeForKatHttp3Retry()) {
                provider.recordDecision(host, ChannelDecision.UNKNOWN)
                provider.diagnostic(
                    "ech_request_skipped",
                    "host=$host method=${request.method} reason=connect_failure_non_replayable",
                )
                throw error
            }
            val diagnosticId = nextDiagnosticId.incrementAndGet()
            provider.diagnostic(
                "ech_request_attempt",
                "id=$diagnosticId host=$host method=${request.method} reason=direct_connect_failure",
            )
            logcat(LogPriority.INFO) { "ECH: kathttp3 retry id=$diagnosticId host=$host reason=direct_connect_failure" }
            return try {
                viaKatHttp3(k3, request, host, diagnosticId)
            } catch (katError: IOException) {
                provider.recordDecision(host, ChannelDecision.UNKNOWN)
                provider.diagnostic(
                    "ech_request_failed",
                    "id=$diagnosticId host=$host " +
                        "reason=kathttp3_after_direct_connect_failure type=${katError.javaClass.simpleName}",
                )
                if (useShortConnectTimeout) {
                    return try {
                        chain.proceed(request).also { fallbackResponse ->
                            provider.recordDecision(
                                host,
                                if (fallbackResponse.isCloudflareChallenge()) {
                                    ChannelDecision.UNKNOWN
                                } else {
                                    ChannelDecision.DIRECT
                                },
                            )
                            provider.diagnostic(
                                "ech_route_fallback_direct",
                                "id=$diagnosticId host=$host method=${request.method} " +
                                    "status=${fallbackResponse.code} reason=short_probe_and_kathttp3_failure",
                            )
                        }
                    } catch (directError: IOException) {
                        directError.addSuppressed(katError)
                        directError.addSuppressed(error)
                        throw directError
                    }
                }
                throw katError
            }
        }

        if (!directResponse.isCloudflareChallenge()) {
            provider.recordDecision(host, ChannelDecision.DIRECT)
            provider.diagnostic(
                "ech_route_direct",
                "host=$host method=${request.method} status=${directResponse.code}",
            )
            return directResponse
        }

        if (!request.isSafeForKatHttp3Retry()) {
            // Do not cache a challenge as DIRECT: a later safe GET may still
            // benefit from ECH. This request itself is not replayed.
            provider.recordDecision(host, ChannelDecision.UNKNOWN)
            provider.diagnostic(
                "ech_request_skipped",
                "host=$host method=${request.method} status=${directResponse.code} reason=challenge_non_replayable",
            )
            return directResponse
        }

        val diagnosticId = nextDiagnosticId.incrementAndGet()
        provider.diagnostic(
            "ech_request_attempt",
            "id=$diagnosticId host=$host method=${request.method} " +
                "status=${directResponse.code} reason=cloudflare_challenge",
        )
        logcat(LogPriority.INFO) {
            "ECH: kathttp3 retry id=$diagnosticId host=$host reason=cloudflare_challenge status=${directResponse.code}"
        }
        return try {
            viaKatHttp3(k3, request, host, diagnosticId).also { directResponse.close() }
        } catch (error: IOException) {
            provider.recordDecision(host, ChannelDecision.UNKNOWN)
            provider.diagnostic(
                "ech_request_failed",
                "id=$diagnosticId host=$host reason=kathttp3_io_error type=${error.javaClass.simpleName}",
            )
            // Preserve the original HTTP response if the ECH transport itself
            // is unavailable; do not turn an ordinary server response into an
            // application-level exception.
            directResponse
        }
    }

    private fun viaKatHttp3(
        k3: KatHttp3Client,
        request: Request,
        host: String,
        diagnosticId: Long,
    ): Response {
        logcat(LogPriority.INFO) { "ECH: kathttp3 transport id=$diagnosticId host=$host" }
        val k3Request = KatHttp3Request(
            method = request.method,
            url = request.url.toString(),
            headers = request.headers.names().flatMap { name ->
                request.headers.values(name).map { KatHttp3Header(name, it) }
            },
        )
        val call = k3.executeStreaming(k3Request)
        val headLatch = CountDownLatch(1)
        val statusRef = AtomicReference(0)
        val headerRef = AtomicReference<List<Pair<String, String>>>(emptyList())
        val bodyQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED_BODY_CHUNKS)
        val streamClosed = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val errorRef = AtomicReference<Throwable?>()

        fun enqueueBody(bytes: ByteArray) {
            while (!streamClosed.get()) {
                if (bodyQueue.offer(bytes, BODY_QUEUE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return
            }
        }

        scope.launch {
            try {
                call.events.collect { event ->
                    when (event) {
                        is KatHttp3StreamEvent.Headers -> {
                            statusRef.set(event.status)
                            headerRef.set(event.headers.map { it.name to it.value })
                            headLatch.countDown()
                        }
                        is KatHttp3StreamEvent.Body -> if (event.bytes.isNotEmpty()) enqueueBody(event.bytes)
                    }
                }
            } catch (t: Throwable) {
                if (!streamClosed.get()) errorRef.set(t)
            } finally {
                headLatch.countDown()
                enqueueBody(ByteArray(0)) // EOF sentinel; errorRef is checked before EOF is returned.
                finished.set(true)
            }
        }

        if (!headLatch.await(15, TimeUnit.SECONDS)) {
            streamClosed.set(true)
            bodyQueue.clear()
            call.cancel()
            throw IOException("ECH: response headers timeout for $host")
        }
        val status = statusRef.get()
        if (status == 0) {
            streamClosed.set(true)
            bodyQueue.clear()
            call.cancel()
            val failure = errorRef.get()
            if (failure is IOException) throw failure
            throw IOException("ECH: kathttp3 ended before response headers for $host", failure)
        }
        val headers = headerRef.get()
        val contentType = headers.firstOrNull { it.first.equals("content-type", true) }?.second
            ?.toMediaTypeOrNull()
        val contentLength = headers.firstOrNull { it.first.equals("content-length", true) }
            ?.second?.toLongOrNull() ?: -1L

        val body = object : ResponseBody() {
            override fun contentType() = contentType
            override fun contentLength(): Long = contentLength
            override fun source(): BufferedSource =
                KatHttp3Source(bodyQueue, finished, errorRef) {
                    if (streamClosed.compareAndSet(false, true)) {
                        bodyQueue.clear()
                        bodyQueue.offer(ByteArray(0))
                        call.cancel()
                    }
                }.buffer()
        }

        val okHeaders = okhttp3.Headers.Builder().apply {
            headers.forEach { (name, value) ->
                // ResponseBody.contentLength() is authoritative.
                if (!name.equals("content-length", true)) add(name, value)
            }
        }.build()

        providerRecordResult(host, diagnosticId, status)
        return Response.Builder()
            .request(request)
            // OkHttp has no HTTP/3 enum; HTTP/2 is the closest available value.
            .protocol(Protocol.HTTP_2)
            .code(status)
            .message("")
            .headers(okHeaders)
            .body(body)
            .build()
    }

    private fun providerRecordResult(host: String, diagnosticId: Long, status: Int) {
        val provider = EchProxyRegistry.provider ?: return
        provider.recordDecision(host, ChannelDecision.KAT_HTTP3)
        provider.diagnostic(
            "ech_request_result",
            "id=$diagnosticId host=$host transport=kathttp3 status=$status handshake=connection_scoped_native_logcat",
        )
        logcat(LogPriority.INFO) {
            "ECH: kathttp3 response id=$diagnosticId host=$host status=$status; " +
                "connection handshake acceptance is logged by kathttp3 as 'ECH negotiated=<0|1>'"
        }
    }
}

/** Only body-less safe methods can be replayed over kathttp3. */
internal fun Request.isSafeForKatHttp3Retry(): Boolean =
    body == null && method in setOf("GET", "HEAD", "OPTIONS")

/**
 * Routes a Cloudflare challenge/block response to kathttp3 only when retrying
 * the original request cannot duplicate a non-idempotent operation.
 */
internal fun shouldRetryChallengeViaKatHttp3(request: Request, response: Response): Boolean =
    request.isSafeForKatHttp3Retry() && response.isCloudflareChallenge()

/** Bridges kathttp3's streaming events to an okio [Source] with bounded buffering. */
internal class KatHttp3Source(
    private val queue: LinkedBlockingQueue<ByteArray>,
    private val finished: AtomicBoolean,
    private val errorRef: AtomicReference<Throwable?>,
    private val onClose: () -> Unit,
) : Source {
    private val closed = AtomicBoolean(false)
    private var currentChunk: ByteArray? = null
    private var currentOffset = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (closed.get()) throw IOException("closed")
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L

        while (true) {
            val chunk = currentChunk
            if (chunk != null && currentOffset < chunk.size) {
                val count = minOf(byteCount, (chunk.size - currentOffset).toLong()).toInt()
                sink.write(chunk, currentOffset, count)
                currentOffset += count
                if (currentOffset == chunk.size) {
                    currentChunk = null
                    currentOffset = 0
                }
                return count.toLong()
            }

            currentChunk = null
            currentOffset = 0
            val next = queue.poll(30, TimeUnit.SECONDS)
            if (closed.get()) throw IOException("closed")
            if (next == null) {
                throwReadFailure()
                if (finished.get()) return -1L
                throw IOException("kathttp3 stream read timeout")
            }
            if (next.isEmpty()) {
                throwReadFailure()
                return -1L
            }
            currentChunk = next
        }
    }

    private fun throwReadFailure() {
        errorRef.get()?.let { failure ->
            if (failure is IOException) throw failure
            throw IOException("kathttp3 response body failed", failure)
        }
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}
