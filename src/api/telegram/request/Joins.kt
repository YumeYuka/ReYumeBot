package api.telegram.request

import api.telegram.common.TelegramId
import api.telegram.common.UnixTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 创建需要审批的入群邀请链接。
 *
 * @param chatId 目标群 ID
 * @param name 邀请链接名称
 * @param expireDate 过期时间
 * @param createsJoinRequest 是否开启入群审批
 */
@Serializable
data class CreateChatInviteLinkRequest(
    @SerialName("chat_id") val chatId: TelegramId,
    val name: String? = null,
    @SerialName("expire_date") val expireDate: UnixTime? = null,
    @SerialName("creates_join_request") val createsJoinRequest: Boolean = true,
)

/**
 * 通过入群申请。
 *
 * @param chatId 目标群 ID
 * @param userId 申请用户 ID
 */
@Serializable
data class ApproveChatJoinRequest(
    @SerialName("chat_id") val chatId: TelegramId,
    @SerialName("user_id") val userId: TelegramId,
)

/**
 * 拒绝入群申请。
 *
 * @param chatId 目标群 ID
 * @param userId 申请用户 ID
 */
@Serializable
data class DeclineChatJoinRequest(
    @SerialName("chat_id") val chatId: TelegramId,
    @SerialName("user_id") val userId: TelegramId,
)
