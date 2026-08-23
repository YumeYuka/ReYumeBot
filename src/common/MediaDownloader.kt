package common

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private const val DOWNLOAD_MAX_ATTEMPTS = 4
private const val DOWNLOAD_RETRY_BASE_DELAY_MILLIS = 1_000L

/**
 * 流式媒体下载器：不限总请求时长（文件可能几百 MB），仅靠连接/停滞超时兜底；
 * 支持 Range 断点续传，失败自动重试；校验 Content-Length，不完整直接报错。
 */
class MediaDownloader(
    private val label: String = "媒体",
) : AutoCloseable {
    private val logger = logger<MediaDownloader>()
    private val client =
        HttpClient(Curl) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = 120_000L
            }
        }

    suspend fun download(
        url: String,
        outputPath: Path,
        configure: HttpRequestBuilder.() -> Unit = {},
    ) {
        var attempt = 0
        while (true) {
            attempt++
            val resumeFrom = fileSize(outputPath)
            try {
                streamToFile(url, outputPath, resumeFrom, configure)
                return
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (attempt >= DOWNLOAD_MAX_ATTEMPTS) {
                    if (SystemFileSystem.exists(outputPath)) SystemFileSystem.delete(outputPath)
                    throw IllegalStateException("${label}下载失败（重试 $DOWNLOAD_MAX_ATTEMPTS 次后放弃）：${error.message}")
                }
                logger.warn("$label download attempt $attempt failed, resuming at ${fileSize(outputPath)} bytes: ${error.message}")
                delay(DOWNLOAD_RETRY_BASE_DELAY_MILLIS * attempt)
            }
        }
    }

    private suspend fun streamToFile(
        url: String,
        outputPath: Path,
        resumeFrom: Long,
        configure: HttpRequestBuilder.() -> Unit,
    ) {
        var expectedTotal = -1L
        client
            .prepareGet(url) {
                configure()
                if (resumeFrom > 0) header(HttpHeaders.Range, "bytes=$resumeFrom-")
            }.execute { response ->
                val appending = resumeFrom > 0 && response.status.value == 206
                when (response.status.value) {
                    in 200..299 -> Unit
                    403, 410 -> error("${label}下载地址已过期（${response.status.value}）。")
                    416 -> error("${label}下载范围请求被拒绝（416）。")
                    else -> error("${label}下载失败：${response.status.value}")
                }
                val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
                expectedTotal = if (appending && contentLength > 0) resumeFrom + contentLength else contentLength
                if (!appending && resumeFrom > 0 && SystemFileSystem.exists(outputPath)) SystemFileSystem.delete(outputPath)
                val channel = response.bodyAsChannel()
                SystemFileSystem.sink(outputPath, append = appending).buffered().use { sink ->
                    channel.readTo(sink)
                }
            }
        val actualSize = fileSize(outputPath)
        require(actualSize > 0) { "${label}下载结果为空。" }
        if (expectedTotal > 0) {
            require(actualSize >= expectedTotal) { "${label}下载不完整：$actualSize/$expectedTotal 字节。" }
        }
    }

    private fun fileSize(path: Path): Long = if (SystemFileSystem.exists(path)) SystemFileSystem.metadataOrNull(path)?.size ?: 0L else 0L

    override fun close() = client.close()
}
