package api.telegram.join

import api.telegram.common.TelegramId
import api.telegram.common.UnixTime
import api.telegram.core.Chat
import api.telegram.core.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户入群申请。
 *
 * 只有开启“需要管理员批准”的邀请链接时才会收到。
 *
 * @param chat 目标聊天
 * @param from 申请入群的用户
 * @param userChatId bot 可临时私聊该用户的 chatId
 * @param date 申请时间
 * @param bio 用户简介
 * @param inviteLink 用户使用的邀请链接
 */
@Serializable
data class ChatJoinRequest(
    val chat: Chat,
    val from: User,
    @SerialName("user_chat_id") val userChatId: TelegramId,
    val date: UnixTime,
    val bio: String? = null,
    @SerialName("invite_link") val inviteLink: ChatInviteLink? = null,
)
