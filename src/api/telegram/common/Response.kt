package api.telegram.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Telegram Bot API 通用响应结构。
 *
 * @param ok 请求是否成功
 * @param result 成功时返回的数据
 * @param description 错误或状态描述
 * @param errorCode 错误码
 * @param parameters 附加错误参数
 */
@Serializable
data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val parameters: ResponseParameters? = null,
)

/**
 * Telegram API 错误附加参数。
 *
 * @param retryAfter 限流后需要等待的秒数
 * @param migrateToChatId 群组升级为超级群后的新 chatId
 */
@Serializable
data class ResponseParameters(
    @SerialName("retry_after") val retryAfter: Int? = null,
    @SerialName("migrate_to_chat_id") val migrateToChatId: TelegramId? = null,
)
