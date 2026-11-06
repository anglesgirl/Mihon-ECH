package eu.kanade.tachiyomi.network

import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat

/**
 * GitHub 镜像拦截器：把 github.com 的 releases/download 与 raw 请求改走自建 EdgeOne 镜像。
 *
 * 改写规则（必须同时满足，缺一不可）：
 * - host 是 github.com（api.github.com 等其他 host 一律不动）
 * - scheme 是 https
 * - 路径形如 /<owner>/<repo>/releases/download/<tag>/<file>，或 /<owner>/<repo>/raw/<branch>/<path>
 * 改写方式只换 host 为 [MIRROR_HOST]，路径和查询参数原样不动。
 *
 * raw 的用途：扩展商店索引 index.pb 由 Keiyoushi 的 repo.json 以 index_v2 硬编码为
 * github.com/.../raw/... 地址，不经此改写会绕开镜像直连 GitHub，导致新装商店为空、
 * 手动填镜像地址也仍拉 GitHub。
 *
 * 回落：镜像请求抛异常或返回非 2xx 时，关闭镜像响应（若有），用原始 github.com 地址重试一次，
 * 返回重试结果。镜像失败绝不能导致下载失败。
 */
class GithubReleaseMirrorInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isMirrorableGithubUrl(request)) {
            return chain.proceed(request)
        }

        val mirrored = request.newBuilder()
            .url(request.url.newBuilder().host(MIRROR_HOST).build())
            .build()
        try {
            val response = chain.proceed(mirrored)
            if (response.isSuccessful) {
                return response
            }
            logcat(LogPriority.INFO) {
                "Mirror: $MIRROR_HOST returned ${response.code}, falling back to github.com"
            }
            response.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.INFO, e) {
                "Mirror: $MIRROR_HOST request failed, falling back to github.com"
            }
        }
        return chain.proceed(request)
    }

    private fun isMirrorableGithubUrl(request: Request): Boolean {
        val url = request.url
        if (url.scheme != "https" || url.host != "github.com") {
            return false
        }
        val segments = url.pathSegments
        if (segments.size < 4 || segments[0].isEmpty() || segments[1].isEmpty()) {
            return false
        }
        return when (segments[2]) {
            "releases" ->
                segments.size >= 6 &&
                    segments[3] == "download" &&
                    segments[4].isNotEmpty() &&
                    segments[5].isNotEmpty()
            "raw" ->
                segments.size >= 5 &&
                    segments[3].isNotEmpty() &&
                    segments[4].isNotEmpty()
            else -> false
        }
    }

    companion object {
        /** 自建 EdgeOne 镜像域名（源站 github.com，Host github.com，回源跟随重定向）。 */
        private const val MIRROR_HOST = "gh.anglesya.win"
    }
}
