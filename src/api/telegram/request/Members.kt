package api.telegram.request

import api.telegram.common.TelegramId
import api.telegram.common.UnixTime
import api.telegram.permission.ChatPermissions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 获取指定成员在群内的身份与管理权限。
 *
 * @param chatId 目标群 ID
 * @param userId 目标用户 ID
 */
@Serializable
data class GetChatMemberRequest(
    @SerialName("chat_id") val chatId: TelegramId,
    @SerialName("user_id") val userId: TelegramId,
)

/**
 * 封禁或踢出成员。
 *
 * untilDate 为 null 或超过 Telegram 规则范围时，通常视为永久封禁。
 *
 * @param chatId 目标群 ID
 * @param userId 目标用户 ID
 * @param untilDate 解封时间
 * @param revokeMessages 是否删除该用户历史消息
 */
@Serializable
data class BanChatMemberRequest(
    @SerialName("chat_id") val chatId: TelegramId,
    @SerialName("user_id") val userId: TelegramId,
    @SerialName("until_date") val untilDate: UnixTime? = null,
    @SerialName("revoke_messages") val revokeMessages: Boolean? = true,
)

/**
 * 解封成员。
 *
 * onlyIfBanned = false 时，如果用户当前在群里，也可能被移出群。
 *
 * @param chatId 目标群 ID
 * @param userId 目标用户 ID
 * @param onlyIfBanned 仅当用户已被封禁时执行
 */
@Serializable
data class UnbanChatMemberRequest(
    @SerialName("chat_id") val chatId: TelegramId,
    @SerialName("user_id") val userId: TelegramId,
    @SerialName("only_if_banned") val onlyIfBanned: Boolean? = true,
)

/**
 * 限制成员权限。
 *
 * @param chatId 目标群 ID
 * @param userId 目标用户 ID
 * @param permissions 权限配置
 * @param untilDate 限制解除时间
 */
@Serializable
data class RestrictChatMemberRequest(
    @SerialName("chat_id") val chatId: TelegramId,
    @SerialName("user_id") val userId: TelegramId,
    val permissions: ChatPermissions,
    @SerialName("until_date") val untilDate: UnixTime? = null,
)
