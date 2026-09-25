package eu.kanade.tachiyomi.network

import dev.kathttp3.KatHttp3Call
import dev.kathttp3.KatHttp3Client
import dev.kathttp3.KatHttp3Header
import dev.kathttp3.KatHttp3Request
import dev.kathttp3.KatHttp3StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
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
import java.util.concurrent.atomic.AtomicReference

/**
 * 失败驱动的 ECH/H3 路由拦截器（替换 Go 本地代理方案）。
 *
 * 流程（零开销优先，失败才介入）：
 * 1. 已知可直连域名 → 原样放行 OkHttp（零开销）。
 * 2. 未知域名 → OkHttp 快速探测（connect 压到 5s，避免被墙域名 30s 白等）
 *    - 通 → 记 DIRECT，放行。
 *    - 失败（超时/重置，被墙特征）→ 记 KAT_HTTP3，走 kathttp3。
 * 3. kathttp3 通道：
 *    - CF 托管（AS13335）→ 注入 ECH（目标域名配置，或 CF 官方活值兜底）+ H3
 *    - 非 CF → 纯 H3（UDP 通道，绕过 TCP RST）
 *    成功后记 KAT_HTTP3，后续直接走 kathttp3，不再探测。
 */
class EchRoutingInterceptor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.scheme != "https") return chain.proceed(request)
        val host = request.url.host
        val provider = EchProxyRegistry.provider
        val k3 = provider?.katHttp3() ?: return chain.proceed(request)

        when (provider.decision(host)) {
            ChannelDecision.DIRECT -> return chain.proceed(request)
            ChannelDecision.KAT_HTTP3 -> return viaKatHttp3(k3, request, host)
            ChannelDecision.UNKNOWN -> Unit
        }

        // 未知域名：OkHttp 快速探测（被墙域名 30s 超时太慢，压到 5s）
        val probe = runCatching {
            chain.withConnectTimeout(5, TimeUnit.SECONDS).proceed(request)
        }
        if (probe.isSuccess) {
            provider.recordDecision(host, ChannelDecision.DIRECT)
            return probe.getOrThrow()
        }
        provider.recordFailure(host)
        return viaKatHttp3(k3, request, host)
    }

    private fun viaKatHttp3(
        k3: KatHttp3Client,
        request: okhttp3.Request,
        host: String,
    ): Response {
        logcat(LogPriority.INFO) { "ECH: kathttp3 transport host=$host" }
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
        val bodyQueue = LinkedBlockingQueue<ByteArray>()
        val finished = AtomicBoolean(false)
        val errorRef = AtomicReference<Throwable?>()

        scope.launch {
            try {
                call.events.collect { event ->
                    when (event) {
                        is KatHttp3StreamEvent.Headers -> {
                            statusRef.set(event.status)
                            headerRef.set(event.headers.map { it.name to it.value })
                            headLatch.countDown()
                        }
                        is KatHttp3StreamEvent.Body -> bodyQueue.put(event.bytes)
                    }
                }
            } catch (t: Throwable) {
                errorRef.set(t)
            } finally {
                finished.set(true)
                bodyQueue.put(ByteArray(0)) // EOF 哨兵
            }
        }

        if (!headLatch.await(15, TimeUnit.SECONDS)) {
            call.cancel()
            throw IOException("ECH: response headers timeout for $host")
        }
        val status = statusRef.get()
        val headers = headerRef.get()
        val contentType = headers.firstOrNull { it.first.equals("content-type", true) }?.second
            ?.toMediaTypeOrNull()
        val contentLength = headers.firstOrNull { it.first.equals("content-length", true) }
            ?.second?.toLongOrNull() ?: -1L

        val body = object : ResponseBody() {
            override fun contentType() = contentType
            override fun contentLength(): Long = contentLength
            override fun source(): BufferedSource =
                KatHttp3Source(bodyQueue, finished, errorRef) { call.cancel() }.buffer()
        }

        val okHeaders = okhttp3.Headers.Builder().apply {
            headers.forEach { (name, value) ->
                // content-length 由 ResponseBody.contentLength() 提供，避免双源
                if (!name.equals("content-length", true)) add(name, value)
            }
        }.build()

        provider.recordDecision(host, ChannelDecision.KAT_HTTP3)
        return Response.Builder()
            .request(request)
            // OkHttp 枚举无 HTTP/3，用 QUIC 语义最接近；对消费者无实质影响
            .protocol(Protocol.HTTP_2)
            .code(status)
            .message("")
            .headers(okHeaders)
            .body(body)
            .build()
    }
}

/** 把 kathttp3 的流式事件桥接成 okio [Source]，边收边给，不整响应缓冲。 */
private class KatHttp3Source(
    private val queue: LinkedBlockingQueue<ByteArray>,
    private val finished: AtomicBoolean,
    private val errorRef: AtomicReference<Throwable?>,
    private val onClose: () -> Unit,
) : Source {

    override fun read(sink: Buffer, byteCount: Long): Long {
        val chunk = queue.poll(30, TimeUnit.SECONDS)
        if (chunk == null) {
            errorRef.get()?.let { throw it }
            if (finished.get()) return -1L // EOF
            throw IOException("kathttp3 stream read timeout")
        }
        if (chunk.isEmpty()) return -1L // EOF 哨兵
        sink.write(chunk)
        return chunk.size.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = onClose()
}
