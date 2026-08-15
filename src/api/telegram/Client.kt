package api.telegram

import api.telegram.common.TelegramResponse
import common.Logger
import common.logger
import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

/**
 * 将单个 Bot API 方法名与 JSON 请求体投递到 Telegram 服务器，并返回**原始响应体字符串**。
 *
 * 职责边界：仅负责 HTTP 与状态码校验，不解析 `ok` / `result` 信封。上层由 [TelegramBotClient] 负责 [TelegramResponse]
 * 解码与业务错误处理。
 *
 * 实现可替换（例如单测 Fake、代理网关），此时由调用方自行管理底层连接生命周期。
 */
fun interface TelegramBotHttpTransport {
    /**
     * POST `https://api.telegram.org/bot<token>/<method>`（或由实现约定的等价地址）。
     *
     * @param method Bot API 方法名片段，例如 `sendMessage`、`getUpdates`
     * @param body 已序列化为 JSON Object 的请求体
     * @return 响应体文本（通常为 JSON）
     * @throws IllegalStateException 当 HTTP 状态非成功时（与默认 Ktor 实现行为一致，便于统一处理）
     */
    suspend fun post(method: String, body: JsonObject): String
}

/** Telegram Bot API 调用端使用的默认 JSON 配置（忽略未知字段、省略显式 null）。 */
fun telegramBotDefaultJson(): Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private const val TELEGRAM_REQUEST_TIMEOUT_MILLIS = 75_000L
private const val TELEGRAM_CONNECT_TIMEOUT_MILLIS = 10_000L

private fun createTelegramHttpClient(json: Json): HttpClient =
    HttpClient(Curl) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = TELEGRAM_REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = TELEGRAM_CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = TELEGRAM_REQUEST_TIMEOUT_MILLIS
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

private class KtorTelegramBotHttpTransport(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val logger: Logger,
) : TelegramBotHttpTransport {
    override suspend fun post(method: String, body: JsonObject): String {
        val response =
            httpClient.post("$baseUrl/$method") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val errorMessage = "Telegram HTTP ${response.status.value}: $bodyText"
            logger.error(errorMessage)
            error(errorMessage)
        }
        return bodyText
    }
}

/**
 * Telegram Bot API 的编排入口：在 [TelegramBotHttpTransport] 之上完成 JSON 编解码与 [TelegramResponse] 信封解析，并在 `ok
 * == false` 或缺少 `result` 时失败。
 *
 * @param botToken Bot Token；会 `trim()`，且不允许为空
 * @param json 与 HTTP Client 内容协商共用的序列化配置；默认 [telegramBotDefaultJson]
 * @param transport 自定义传输层；为 `null` 时使用内置 HTTP 客户端（由本类 [close] 负责释放）
 */
class TelegramBotClient(
    botToken: String,
    private val json: Json = telegramBotDefaultJson(),
    transport: TelegramBotHttpTransport? = null,
) : AutoCloseable {

    private val logger = logger<TelegramBotClient>()

    private val token =
        botToken.trim().also {
            require(it.isNotEmpty()) { "Telegram bot token must not be blank" }
        }

    internal val apiBaseUrl = "https://api.telegram.org/bot$token"

    private val httpClient: HttpClient? =
        if (transport == null) createTelegramHttpClient(json) else null

    private val transport: TelegramBotHttpTransport =
        transport
            ?: KtorTelegramBotHttpTransport(
                httpClient = httpClient!!,
                baseUrl = apiBaseUrl,
                logger = logger,
            )

    /**
     * 将可序列化请求体编码为 JSON Object 后调用 [method]，并将 `result` 反序列化为 [T]。
     *
     * @param R 请求 DTO 类型（须注册 kotlinx.serialization）
     * @param T `result` 字段对应类型
     */
    suspend inline fun <reified R, reified T> execute(
        method: String,
        request: R,
    ): T = execute(method, encodeToJsonObject(request, serializer<R>()), serializer<T>())

    /** 使用已构建的 JSON Object 作为请求体调用 [method]，并将 `result` 反序列化为 [T]。 */
    suspend inline fun <reified T> execute(
        method: String,
        body: JsonObject,
    ): T = execute(method, body, serializer<T>())

    /**
     * 与 [execute] 的 `reified` 版本相同，但允许传入运行时 [KSerializer]（例如泛型或未用 reified 的场景）。
     *
     * @throws IllegalStateException HTTP 失败、或 Telegram 返回 `ok == false` / 无 `result`
     */
    suspend fun <T> execute(
        method: String,
        body: JsonObject,
        resultSerializer: KSerializer<T>,
    ): T {
        val bodyText = transport.post(method, body)
        val telegramResponse = decodeTelegramResponse(bodyText, resultSerializer)
        if (!telegramResponse.ok || telegramResponse.result == null) {
            val errorMessage =
                "Telegram $method failed: ${telegramResponse.errorCode} ${telegramResponse.description}"
            logger.error(errorMessage)
            error(errorMessage)
        }
        return telegramResponse.result
    }

    /** 将 [value] 编码为 JSON Object（仅包含对象顶层字段，供 Bot API POST 使用）。 */
    fun <T> encodeToJsonObject(
        value: T,
        serializer: KSerializer<T>,
    ): JsonObject = json.encodeToJsonElement(serializer, value).jsonObject

    /** 使用 [json] 将 JSON 文本解码为 [T]。 */
    inline fun <reified T> decode(payload: String): T = decode(payload, serializer<T>())

    fun <T> decode(
        payload: String,
        serializer: KSerializer<T>,
    ): T = json.decodeFromString(serializer, payload)

    /** 解析 Telegram 标准响应信封；不根据 `ok` 抛错，由 [execute] 统一判定。 */
    fun <T> decodeTelegramResponse(
        payload: String,
        resultSerializer: KSerializer<T>,
    ): TelegramResponse<T> =
        json.decodeFromString(TelegramResponse.serializer(resultSerializer), payload)

    override fun close() {
        httpClient?.close()
    }
}
