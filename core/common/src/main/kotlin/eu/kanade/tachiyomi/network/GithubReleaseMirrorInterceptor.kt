package eu.kanade.tachiyomi.network

import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat

/**
 * GitHub release 镜像拦截器：把 github.com 的 releases/download 请求改走自建 EdgeOne 镜像。
 *
 * 改写规则（必须同时满足，缺一不可）：
 * - host 是 github.com（api.github.com 等其他 host 一律不动）
 * - scheme 是 https
 * - 路径形如 /<owner>/<repo>/releases/download/<tag>/<file>
 * 改写方式只换 host 为 [MIRROR_HOST]，路径和查询参数原样不动。
 *
 * 回落：镜像请求抛异常或返回非 2xx 时，关闭镜像响应（若有），用原始 github.com 地址重试一次，
 * 返回重试结果。镜像失败绝不能导致下载失败。
 */
class GithubReleaseMirrorInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isGithubReleaseDownload(request)) {
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

    private fun isGithubReleaseDownload(request: Request): Boolean {
        val url = request.url
        if (url.scheme != "https" || url.host != "github.com") {
            return false
        }
        val segments = url.pathSegments
        return segments.size >= 6 &&
            segments[2] == "releases" &&
            segments[3] == "download" &&
            segments[0].isNotEmpty() &&
            segments[1].isNotEmpty() &&
            segments[4].isNotEmpty() &&
            segments[5].isNotEmpty()
    }

    companion object {
        /** 自建 EdgeOne 镜像域名（源站 github.com，Host github.com，回源跟随重定向）。 */
        private const val MIRROR_HOST = "gh.anglesya.win"
    }
}
