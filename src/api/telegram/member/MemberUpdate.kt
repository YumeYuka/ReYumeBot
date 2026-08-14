package api.telegram.member

import api.telegram.common.UnixTime
import api.telegram.core.Chat
import api.telegram.core.User
import api.telegram.join.ChatInviteLink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 群成员状态变更事件。
 *
 * 可用于判断用户是否入群、退群、被踢、被封禁。
 *
 * @param chat 发生变更的聊天
 * @param from 操作者
 * @param date 变更时间
 * @param oldChatMember 变更前成员状态
 * @param newChatMember 变更后成员状态
 * @param inviteLink 入群时使用的邀请码链接
 * @param viaJoinRequest 是否通过入群申请审批后加入
 * @param viaChatFolderInviteLink 是否通过聊天文件夹邀请链接加入
 */
@Serializable
data class ChatMemberUpdated(
    val chat: Chat,
    val from: User,
    val date: UnixTime,
    @SerialName("old_chat_member") val oldChatMember: ChatMember,
    @SerialName("new_chat_member") val newChatMember: ChatMember,
    @SerialName("invite_link") val inviteLink: ChatInviteLink? = null,
    @SerialName("via_join_request") val viaJoinRequest: Boolean? = null,
    @SerialName("via_chat_folder_invite_link") val viaChatFolderInviteLink: Boolean? = null,
)
