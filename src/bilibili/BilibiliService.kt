package bilibili

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

import common.logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getenv
import platform.posix.system

private const val BILIBILI_ORIGIN = "https://www.bilibili.com"
private const val BILIBILI_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val MAX_VIDEO_DURATION_SECONDS = 10 * 60
private const val LOGIN_POLL_DELAY_MILLIS = 2_000L
private const val LOGIN_TIMEOUT_MILLIS = 180_000L
private const val MAX_QUALITY = 120
private const val DASH_FNVAL = 4048

private val bvidPattern = Regex("(?i)BV[0-9A-Za-z]{10}")
private val avidPattern = Regex("(?i)av(\\d+)")
private val b23Pattern = Regex("https?://b23\\.tv/[^\\s<>()]+", RegexOption.IGNORE_CASE)
private val videoPattern = Regex("https?://(?:www\\.)?bilibili\\.com/video/[^\\s<>()]+", RegexOption.IGNORE_CASE)

@Serializable
data class BilibiliCredentials(
    @SerialName("SESSDATA") val sessData: String = "",
    @SerialName("bili_jct") val biliJct: String = "",
    @SerialName("DedeUserID") val dedeUserId: String = "",
    @SerialName("DedeUserID__ckMd5") val dedeUserIdChecksum: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
) {
    fun asCookieHeader(): String = listOf(
        "SESSDATA" to sessData,
        "bili_jct" to biliJct,
        "DedeUserID" to dedeUserId,
        "DedeUserID__ckMd5" to dedeUserIdChecksum,
    ).filter { (_, value) -> value.isNotBlank() }
        .joinToString("; ") { (name, value) -> "$name=$value" }
}

data class BilibiliLoginQrCode(val loginUrl: String, val qrCodeKey: String)

enum class BilibiliLoginStatus { SUCCESS, EXPIRED, TIMEOUT }

data class BilibiliDownloadedVideo(
    val title: String,
    val summary: String,
    val sourceUrl: String,
    val filePath: String,
)

class BilibiliApiException(
    operation: String,
    val errorCode: Int?,
    message: String,
) : IllegalStateException(buildErrorMessage(operation, errorCode, message))

private fun buildErrorMessage(operation: String, errorCode: Int?, message: String): String =
    when (errorCode) {
        -404 -> "视频不存在、已删除或当前不可访问。"
        -101 -> "需要先使用 /bili_login 登录 B 站账号。"
        -403 -> "当前账号没有访问该视频的权限。"
        -412 -> "B站暂时拒绝了请求，请稍后重试。"
        else -> "${operation}失败：${message.ifBlank { "未知错误" }}${errorCode?.let { "（错误码 $it）" }.orEmpty()}"
    }

class BilibiliService(
    private val credentialPath: Path = Path("data/bilibili-credentials.json"),
    private val downloadDirectory: Path = Path("data/bilibili-downloads"),
) : AutoCloseable {
    private val logger = logger<BilibiliService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(Curl) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000L
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 30_000L
        }
    }

    fun extractVideoUrl(text: String): String? =
        b23Pattern.find(text)?.value ?: videoPattern.find(text)?.value
            ?: bvidPattern.find(text)?.value?.let { "$BILIBILI_ORIGIN/video/${it.uppercase()}" }
            ?: avidPattern.find(text)?.value?.let { "$BILIBILI_ORIGIN/video/$it" }

    suspend fun createLoginQrCode(): BilibiliLoginQrCode {
        val payload = requestObject("https://passport.bilibili.com/x/passport-login/web/qrcode/generate", BILIBILI_ORIGIN)
        val loginUrl = payload.string("url")
        val qrCodeKey = payload.string("qrcode_key")
        require(loginUrl.isNotBlank() && qrCodeKey.isNotBlank()) { "B站二维码生成响应不完整。" }
        return BilibiliLoginQrCode(loginUrl, qrCodeKey)
    }

    suspend fun waitForLogin(qrCodeKey: String): BilibiliLoginStatus {
        val deadline = currentTimeMillis() + LOGIN_TIMEOUT_MILLIS
        while (currentTimeMillis() < deadline) {
            delay(LOGIN_POLL_DELAY_MILLIS)
            val response = httpClient.get("https://passport.bilibili.com/x/passport-login/web/qrcode/poll") {
                applyHeaders(BILIBILI_ORIGIN)
                parameter("qrcode_key", qrCodeKey)
            }
            val payload = parseObject(response.bodyAsText(), "B站扫码登录")
            when (payload.int("code")) {
                0 -> {
                    saveCredentials(BilibiliCredentials(
                        sessData = extractCookie(response, "SESSDATA"),
                        biliJct = extractCookie(response, "bili_jct"),
                        dedeUserId = extractCookie(response, "DedeUserID"),
                        dedeUserIdChecksum = extractCookie(response, "DedeUserID__ckMd5"),
                        refreshToken = payload.string("refresh_token"),
                    ))
                    return BilibiliLoginStatus.SUCCESS
                }
                86038 -> return BilibiliLoginStatus.EXPIRED
                86090, 86101 -> Unit
                else -> error("B站扫码登录返回未知状态。")
            }
        }
        return BilibiliLoginStatus.TIMEOUT
    }

    suspend fun downloadVideo(sourceUrl: String): BilibiliDownloadedVideo {
        val pageUrl = expandShortUrl(sourceUrl)
        val target = VideoTarget.fromUrl(pageUrl) ?: error("无法识别 B 站视频链接。")
        val cookieHeader = loadCredentials().asCookieHeader()

        logger.info("Bilibili page lookup started")
        val page = resolvePage(target, pageUrl, cookieHeader)
        logger.info("Bilibili page resolved: durationSeconds=${page.durationSeconds}")

        val metadata = resolveVideoMetadata(target, pageUrl, cookieHeader, page)
        require(metadata.durationSeconds in 1..MAX_VIDEO_DURATION_SECONDS) { "视频时长超过 10 分钟，未发送。" }

        val streams = resolveStreams(target, page.cid, pageUrl, cookieHeader)
        logger.info("Bilibili playback stream resolved: hasSeparateAudio=${streams.audioUrl != null}")

        val outputPath = Path(downloadDirectory, "${sanitizeFileName(metadata.title)}.mp4")
        SystemFileSystem.createDirectories(downloadDirectory)
        logger.info("Bilibili media download started")
        downloadStreams(streams, outputPath, pageUrl, cookieHeader)
        logger.info("Bilibili media download completed")

        return BilibiliDownloadedVideo(
            title = metadata.title,
            summary = metadata.summary,
            sourceUrl = pageUrl,
            filePath = outputPath.toString(),
        )
    }

    fun deleteDownloadedFile(filePath: String) {
        val path = Path(filePath)
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
    }

    private suspend fun expandShortUrl(sourceUrl: String): String {
        if (!sourceUrl.contains("b23.tv", ignoreCase = true)) return sourceUrl
        val response = httpClient.get(sourceUrl) { applyHeaders(BILIBILI_ORIGIN) }
        return response.call.request.url.toString()
    }

    private suspend fun resolvePage(target: VideoTarget, pageUrl: String, cookieHeader: String): BilibiliPage {
        val pages = requestArray("https://api.bilibili.com/x/player/pagelist", pageUrl, cookieHeader) {
            target.apply(this)
        }
        val pageNumber = Regex("[?&]p=(\\d+)").find(pageUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        require(pageNumber in 1..pages.size) { "请求的分P不存在。" }
        val selectedPage = pages[pageNumber - 1].jsonObject
        return BilibiliPage(
            cid = selectedPage.long("cid"),
            title = selectedPage.string("part").ifBlank { "B站视频" },
            durationSeconds = selectedPage.int("duration"),
        )
    }

    private suspend fun resolveVideoMetadata(
        target: VideoTarget,
        pageUrl: String,
        cookieHeader: String,
        page: BilibiliPage,
    ): BilibiliVideoMetadata =
        try {
            val videoInfo = requestObject("https://api.bilibili.com/x/web-interface/view", pageUrl, cookieHeader) {
                target.apply(this)
            }
            BilibiliVideoMetadata(
                title = videoInfo.string("title").ifBlank { page.title },
                summary = videoInfo.string("desc").trim(),
                durationSeconds = videoInfo.int("duration").takeIf { it > 0 } ?: page.durationSeconds,
            )
        } catch (error: BilibiliApiException) {
            if (error.errorCode != -404) throw error
            logger.warn("Bilibili view metadata unavailable; using pagelist fallback: url=$pageUrl")
            BilibiliVideoMetadata(
                title = page.title,
                summary = "",
                durationSeconds = page.durationSeconds,
            )
        }
    private suspend fun resolveStreams(target: VideoTarget, cid: Long, pageUrl: String, cookieHeader: String): VideoStreams {
        val qualityProbe = requestPlayUrl(target, cid, pageUrl, cookieHeader, MAX_QUALITY, DASH_FNVAL)
        val highestQuality = qualityProbe.array("accept_quality").maxOfOrNull { it.jsonPrimitive.content.toIntOrNull() ?: 0 }
            ?.takeIf { it > 0 } ?: MAX_QUALITY
        val mergedPayload = requestPlayUrl(target, cid, pageUrl, cookieHeader, highestQuality, 0)
        val mergedUrl = mergedPayload.array("durl").firstOrNull()?.jsonObject?.string("url")
        if (!mergedUrl.isNullOrBlank()) return VideoStreams(mergedUrl, null)
        val dashPayload = requestPlayUrl(target, cid, pageUrl, cookieHeader, highestQuality, DASH_FNVAL)
        val dash = dashPayload.objectValue("dash")
        val videoUrl = dash.array("video").maxByOrNull { it.jsonObject.int("id") }?.jsonObject?.baseUrl()
            ?.takeIf { it.isNotBlank() } ?: error("B站未返回可下载的视频流。")
        val audioUrl = dash.array("audio").maxByOrNull { it.jsonObject.int("id") }?.jsonObject?.baseUrl()?.takeIf { it.isNotBlank() }
        return VideoStreams(videoUrl, audioUrl)
    }

    private suspend fun requestPlayUrl(target: VideoTarget, cid: Long, pageUrl: String, cookieHeader: String, quality: Int, fnval: Int): JsonObject =
        requestObject("https://api.bilibili.com/x/player/playurl", pageUrl, cookieHeader) {
            target.apply(this)
            parameter("cid", cid)
            parameter("qn", quality)
            parameter("fnver", 0)
            parameter("fnval", fnval)
            parameter("fourk", 1)
            parameter("otype", "json")
            parameter("platform", "html5")
            parameter("high_quality", 1)
        }

    private suspend fun requestObject(url: String, referer: String, cookieHeader: String = "", configure: HttpRequestBuilder.() -> Unit = {}): JsonObject =
        parseObject(request(url, referer, cookieHeader, configure).bodyAsText(), "B站接口请求")

    private suspend fun requestArray(url: String, referer: String, cookieHeader: String = "", configure: HttpRequestBuilder.() -> Unit = {}): JsonArray =
        parseArray(request(url, referer, cookieHeader, configure).bodyAsText(), "B站接口请求")

    private suspend fun request(url: String, referer: String, cookieHeader: String, configure: HttpRequestBuilder.() -> Unit): HttpResponse =
        httpClient.get(url) {
            applyHeaders(referer, cookieHeader)
            configure()
        }

    private fun parseObject(body: String, operation: String): JsonObject = parseData(body, operation).jsonObject
    private fun parseArray(body: String, operation: String): JsonArray = parseData(body, operation) as? JsonArray ?: error("${operation}响应类型错误。")

    private fun parseData(body: String, operation: String): JsonElement {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { error ->
            throw BilibiliApiException(operation, null, "接口返回了非 JSON 响应：${body.take(120)}")
        }
        val errorCode = root["code"]?.jsonPrimitive?.content?.toIntOrNull()
        if (errorCode != 0) {
            val message = root["message"]?.jsonPrimitive?.content.orEmpty()
            throw BilibiliApiException(operation, errorCode, message)
        }
        return root["data"] ?: throw BilibiliApiException(operation, errorCode, "响应缺少数据。")
    }

    private fun downloadStreams(streams: VideoStreams, outputPath: Path, referer: String, cookieHeader: String) {
        val videoPath = Path(outputPath.parent!!, "${outputPath.name}.video.m4s")
        val audioPath = Path(outputPath.parent!!, "${outputPath.name}.audio.m4s")
        try {
            downloadFile(streams.videoUrl, videoPath, referer, cookieHeader)
            if (streams.audioUrl == null) {
                runCommand("mv ${shellQuote(videoPath.toString())} ${shellQuote(outputPath.toString())}")
                return
            }
            downloadFile(streams.audioUrl, audioPath, referer, cookieHeader)
            runCommand("ffmpeg -y -i ${shellQuote(videoPath.toString())} -i ${shellQuote(audioPath.toString())} -c copy -map 0:v:0 -map 1:a:0 ${shellQuote(outputPath.toString())}")
        } finally {
            if (SystemFileSystem.exists(videoPath)) SystemFileSystem.delete(videoPath)
            if (SystemFileSystem.exists(audioPath)) SystemFileSystem.delete(audioPath)
        }
    }

    private fun downloadFile(url: String, outputPath: Path, referer: String, cookieHeader: String) {
        val cookieArgument = if (cookieHeader.isBlank()) "" else " -H ${shellQuote("Cookie: $cookieHeader")}"
        runCommand("curl --fail --location --silent --show-error -A ${shellQuote(BILIBILI_USER_AGENT)} -e ${shellQuote(referer)} -H ${shellQuote("Origin: $BILIBILI_ORIGIN")}$cookieArgument -o ${shellQuote(outputPath.toString())} ${shellQuote(url)}")
    }

    private fun saveCredentials(credentials: BilibiliCredentials) {
        require(credentials.asCookieHeader().isNotBlank()) { "B站登录未返回有效 Cookie。" }
        SystemFileSystem.createDirectories(credentialPath.parent!!)
        SystemFileSystem.sink(credentialPath).buffered().use { sink ->
            sink.writeString(json.encodeToString(BilibiliCredentials.serializer(), credentials))
        }
    }

    private fun loadCredentials(): BilibiliCredentials {
        if (!SystemFileSystem.exists(credentialPath)) return BilibiliCredentials()
        return SystemFileSystem.source(credentialPath).buffered().use { source ->
            json.decodeFromString(BilibiliCredentials.serializer(), source.readString())
        }
    }

    private fun extractCookie(response: HttpResponse, name: String): String =
        response.headers.getAll(HttpHeaders.SetCookie)
            ?.firstNotNullOfOrNull { Regex("^${Regex.escape(name)}=([^;]+)").find(it)?.groupValues?.get(1) }
            .orEmpty()

    private fun HttpRequestBuilder.applyHeaders(referer: String, cookieHeader: String = "") {
        header(HttpHeaders.UserAgent, BILIBILI_USER_AGENT)
        header(HttpHeaders.Referrer, referer)
        header(HttpHeaders.Origin, BILIBILI_ORIGIN)
        if (cookieHeader.isNotBlank()) header(HttpHeaders.Cookie, cookieHeader)
    }

    override fun close() = httpClient.close()
}

private data class BilibiliPage(val cid: Long, val title: String, val durationSeconds: Int)
private data class BilibiliVideoMetadata(val title: String, val summary: String, val durationSeconds: Int)
private data class VideoStreams(val videoUrl: String, val audioUrl: String?)

private sealed interface VideoTarget {
    fun apply(builder: HttpRequestBuilder)

    data class Bvid(val value: String) : VideoTarget {
        override fun apply(builder: HttpRequestBuilder) { builder.parameter("bvid", value) }
    }

    data class Aid(val value: Long) : VideoTarget {
        override fun apply(builder: HttpRequestBuilder) { builder.parameter("aid", value) }
    }

    companion object {
        fun fromUrl(url: String): VideoTarget? =
            bvidPattern.find(url)?.value?.let { Bvid(it.uppercase()) }
                ?: avidPattern.find(url)?.groupValues?.get(1)?.toLongOrNull()?.let(::Aid)
    }
}

private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.content.orEmpty()
private fun JsonObject.int(name: String): Int = string(name).toIntOrNull() ?: 0
private fun JsonObject.long(name: String): Long = string(name).toLongOrNull() ?: error("B站响应缺少 $name。")
private fun JsonObject.objectValue(name: String): JsonObject = this[name]?.jsonObject ?: error("B站响应缺少 $name。")
private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.baseUrl(): String = string("baseUrl").ifBlank { string("base_url") }
private fun sanitizeFileName(name: String): String = name.replace(Regex("[^0-9A-Za-z._ -]"), "_").take(100).ifBlank { "bilibili-video" }
private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"
private fun runCommand(command: String) { require(system(command) == 0) { "媒体处理失败。" } }

@OptIn(ExperimentalForeignApi::class)
private fun currentTimeMillis(): Long = getenv("SOURCE_DATE_EPOCH")?.toKString()?.toLongOrNull()?.times(1_000) ?: kotlin.time.Clock.System.now().toEpochMilliseconds()