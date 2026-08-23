package app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.getenv

@Serializable
data class Config(
    @SerialName("bot_token") val botToken: String,
    @SerialName("mini_app_url") val miniAppUrl: String? = null,
    @SerialName("bilibili_admin_id") val bilibiliAdminId: Long? = null,
    /** 自建 Bot API Server 地址（如 http://127.0.0.1:8081）；为空时走官方 api.telegram.org（上传上限 50MB）。 */
    @SerialName("telegram_api_base_url") val telegramApiBaseUrl: String? = null,
)

@OptIn(ExperimentalForeignApi::class)
private fun getEnv(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }

fun getConfig(): Config {
    val envToken = getEnv("BOT_TOKEN")
    val envMiniAppUrl = getEnv("MINI_APP_URL")
    val envBilibiliAdminId = getEnv("BILIBILI_ADMIN_ID")?.toLongOrNull()
    val envTelegramApiBaseUrl = getEnv("TELEGRAM_API_BASE_URL")

    if (envToken != null) {
        return Config(
            botToken = envToken,
            miniAppUrl = envMiniAppUrl,
            bilibiliAdminId = envBilibiliAdminId,
            telegramApiBaseUrl = envTelegramApiBaseUrl,
        )
    }

    val configFile = Path("config.json")
    if (SystemFileSystem.exists(configFile)) {
        val source = SystemFileSystem.source(configFile).buffered()
        return try {
            Json.decodeFromString<Config>(source.readString())
        } finally {
            source.close()
        }
    }

    error("Configuration missing: neither BOT_TOKEN environment variable nor config.json was found.")
}