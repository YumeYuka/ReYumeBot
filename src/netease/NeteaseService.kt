package netease

import common.MediaDownloader
import common.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.time.Clock

private const val NETEASE_ORIGIN = "https://music.163.com"
private const val NETEASE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.115 Mobile Safari/537.36"

// 网易云匿名游客 token（无需登录即可获取标准音质）
private const val ANONYMOUS_TOKEN =
    "4ee5f776c9ed1e4d5f031b09e084c6cb333e43ee4a841afeebbef9bbf4b7e4152b51ff20ecb9e8ee9e89ab23044cf50d1609e4781e805e73a138419e5583bc7fd1e5933c52368d9127ba9ce4e2f233bf5a77ba40ea6045ae1fc612ead95d7b0e0edf70a74334194e1a190979f5fc12e9968c3666a981495b33a649814e309366"

// Telegram Bot API 上传上限 50MB，预留余量；超过则逐级降低音质重试
private const val MAX_AUDIO_BYTES = 48L * 1024 * 1024

private val QUALITY_LEVELS = listOf("hires", "exhigh", "standard")

private val shortLinkPattern = Regex("https?://(?:[a-z0-9-]+\\.)?163cn\\.(?:tv|link)/[^\\s<>()]+", RegexOption.IGNORE_CASE)
private val neteaseUrlPattern = Regex("https?://(?:[a-z0-9-]+\\.)?music\\.163\\.com/[^\\s<>()]+", RegexOption.IGNORE_CASE)
private val idQueryPattern = Regex("[?&]id=(\\d{5,20})")
private val songPathPattern = Regex("/song/?(\\d{5,20})")

data class NeteaseDownloadedSong(
    val name: String,
    val artists: String,
    val album: String,
    val trackUrl: String,
    val filePath: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    val bitrateKbps: Int,
    val level: String,
    val format: String,
)

class NeteaseService(
    private val downloadDirectory: Path = Path("data/netease-downloads"),
) : AutoCloseable {
    private val logger = logger<NeteaseService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient =
        HttpClient(Curl) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000L
                connectTimeoutMillis = 10_000L
                socketTimeoutMillis = 30_000L
            }
        }
    private val mediaDownloader = MediaDownloader("网易云音频")

    /** 从消息文本中提取网易云链接（163cn.tv/163cn.link 短链或 music.163.com 链接）。 */
    fun extractSongUrl(text: String): String? = shortLinkPattern.find(text)?.value ?: neteaseUrlPattern.find(text)?.value

    suspend fun downloadSong(sourceUrl: String): NeteaseDownloadedSong {
        val pageUrl = expandShortUrl(sourceUrl)
        val songId = extractSongId(pageUrl) ?: error("无法识别网易云歌曲链接（仅支持单曲，暂不支持歌单/专辑）。")
        val trackUrl = "$NETEASE_ORIGIN/song?id=$songId"

        logger.info("Netease song lookup started: id=$songId")
        val detail = fetchSongDetail(songId)
        val playable = resolvePlayableStream(songId)
        logger.info("Netease stream resolved: id=$songId, level=${playable.level}, format=${playable.format}, size=${playable.sizeBytes}")

        val fileName = sanitizeFileName("${detail.artists} - ${detail.name}")
        val outputPath = Path(downloadDirectory, "$fileName.${playable.format}")
        SystemFileSystem.createDirectories(downloadDirectory)
        mediaDownloader.download(playable.url, outputPath) {
            header(HttpHeaders.UserAgent, NETEASE_USER_AGENT)
            header(HttpHeaders.Referrer, NETEASE_ORIGIN)
        }
        logger.info("Netease audio download completed: id=$songId")

        return NeteaseDownloadedSong(
            name = detail.name,
            artists = detail.artists,
            album = detail.album,
            trackUrl = trackUrl,
            filePath = outputPath.toString(),
            durationSeconds = detail.durationSeconds,
            sizeBytes = playable.sizeBytes,
            bitrateKbps = playable.bitrateKbps,
            level = playable.level,
            format = playable.format,
        )
    }

    fun deleteDownloadedFile(filePath: String) {
        val path = Path(filePath)
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
    }

    private suspend fun expandShortUrl(sourceUrl: String): String {
        if (!sourceUrl.contains("163cn.", ignoreCase = true)) return sourceUrl
        val response = httpClient.get(sourceUrl) { header(HttpHeaders.UserAgent, NETEASE_USER_AGENT) }
        return response.call.request.url
            .toString()
    }

    private fun extractSongId(url: String): String? {
        if (!url.contains("music.163.com", ignoreCase = true)) return null
        // ?id= / #/song?id= / ?id= 出现在 fragment 里的情况统一用同一正则扫全串
        idQueryPattern.find(url)?.let { return it.groupValues[1] }
        // #/song/123 或 /song/123 路径形式；fragment 中 "#" 后的 "?" 已被上面覆盖
        val pathPart = url.substringAfter("music.163.com")
        return songPathPattern.find(pathPart)?.groupValues?.get(1)
    }

    private data class SongDetail(
        val name: String,
        val artists: String,
        val album: String,
        val durationSeconds: Int,
    )

    private data class PlayableStream(
        val url: String,
        val sizeBytes: Long,
        val bitrateKbps: Int,
        val level: String,
        val format: String,
    )

    private suspend fun fetchSongDetail(songId: String): SongDetail {
        val payload = eapiPost("/api/v3/song/detail", """{"c":"[{\"id\":$songId}]"}""")
        val songs = payload["songs"] as? JsonArray ?: error("网易云歌曲详情响应缺少 songs 字段。")
        val song = songs.firstOrNull()?.jsonObject ?: error("歌曲不存在或已下架。")
        val name = song.string("name").ifBlank { "未知歌曲" }
        val artists =
            (song["ar"] as? JsonArray)
                ?.mapNotNull { it.jsonObject.string("name").takeIf(String::isNotBlank) }
                ?.joinToString(" / ")
                .orEmpty()
                .ifBlank { "未知歌手" }
        val album = (song["al"] as? JsonObject)?.string("name").orEmpty()
        val durationSeconds = song.int("dt") / 1000
        return SongDetail(name, artists, album, durationSeconds)
    }

    /** 按音质逐级降级请求播放地址，直到可播放且大小在 Telegram 上传限制内。 */
    private suspend fun resolvePlayableStream(songId: String): PlayableStream {
        var lastFailure = "歌曲无版权或已下架。"
        for (level in QUALITY_LEVELS) {
            val payload = eapiPost("/api/song/enhance/player/url/v1", """{"ids":"[\"$songId\"]","encodeType":"mp3","level":"$level"}""")
            val data = payload["data"] as? JsonArray ?: continue
            val item = data.firstOrNull()?.jsonObject ?: continue
            val url = item.string("url")
            if (item.int("code") != 200 || url.isBlank()) {
                lastFailure = "歌曲无版权、需 VIP 或当前音质不可用。"
                continue
            }
            val sizeBytes = item.long("size")
            if (sizeBytes > MAX_AUDIO_BYTES) {
                lastFailure = "音频文件（${sizeBytes / 1024 / 1024}MB）超过 Telegram 上传限制。"
                continue
            }
            val format = item.string("type").ifBlank { "mp3" }.lowercase()
            return PlayableStream(
                url = url,
                sizeBytes = sizeBytes,
                bitrateKbps = (item.long("br") / 1000).toInt(),
                level = item.string("level").ifBlank { level },
                format = format,
            )
        }
        error(lastFailure)
    }

    /** EAPI 加密 POST：`/api/...` 路径对应 `https://music.163.com/eapi/...` 端点。 */
    private suspend fun eapiPost(
        path: String,
        jsonPayload: String,
    ): JsonObject {
        val endpoint = NETEASE_ORIGIN + path.replaceFirst("/api/", "/eapi/")
        val response =
            httpClient.post(endpoint) {
                header(HttpHeaders.UserAgent, NETEASE_USER_AGENT)
                header(HttpHeaders.Cookie, buildCookie())
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(eapiParams(path, jsonPayload))
            }
        val body = response.bodyAsText()
        val root =
            runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                error("网易云接口返回了非 JSON 响应：${body.take(120)}")
            }
        val code = root.int("code")
        if (code != 200) {
            error("网易云接口请求失败（错误码 $code）。")
        }
        return root
    }

    private fun buildCookie(): String {
        val buildver =
            Clock.System
                .now()
                .epochSeconds
                .toString()
                .take(10)
        return buildString {
            append("appver=8.9.70; buildver=$buildver; resolution=1920x1080; os=android; ")
            append("NMTID=").append(randomNmtid()).append("; ")
            append("MUSIC_A=").append(ANONYMOUS_TOKEN)
        }
    }

    private fun randomNmtid(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return "00" + buildString(30) { repeat(30) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }

    override fun close() {
        httpClient.close()
        mediaDownloader.close()
    }
}

private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.content.orEmpty()

private fun JsonObject.int(name: String): Int = string(name).toIntOrNull() ?: 0

private fun JsonObject.long(name: String): Long = string(name).toLongOrNull() ?: 0L

private fun sanitizeFileName(name: String): String =
    name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .take(80)
        .ifBlank { "netease-song" }
