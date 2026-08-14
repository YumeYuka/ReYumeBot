package api.telegram.core

import api.telegram.common.TelegramId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Telegram 聊天对象。
 *
 * @param id 聊天 ID
 * @param type 聊天类型：private、group、supergroup、channel
 * @param title 群组、超级群、频道标题
 * @param username 公开用户名
 * @param firstName 私聊对象名
 * @param lastName 私聊对象姓
 */
@Serializable
data class Chat(
    val id: TelegramId,
    val type: String,
    val title: String? = null,
    val username: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
)
