package api.telegram.core

import api.telegram.common.TelegramId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Telegram 用户或 Bot。
 *
 * @param id 用户 ID
 * @param isBot 是否为 bot
 * @param firstName 名
 * @param lastName 姓
 * @param username 用户名，不包含 @
 * @param languageCode 用户语言代码
 * @param isPremium 是否为 Premium 用户
 */
@Serializable
data class User(
    val id: TelegramId,
    @SerialName("is_bot") val isBot: Boolean,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("is_premium") val isPremium: Boolean? = null,
)
