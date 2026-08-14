package api.telegram.member

import api.telegram.common.UnixTime
import api.telegram.core.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 群成员信息。
 *
 * status 常用值：
 * - creator
 * - administrator
 * - member
 * - restricted
 * - left
 * - kicked
 *
 * @param status 成员状态
 * @param user 成员用户
 * @param untilDate 限制或封禁解除时间，0 表示永久
 * @param isMember restricted 状态下是否仍是群成员
 * @param canRestrictMembers 是否可限制、封禁、解封成员
 * @param canDeleteMessages 是否可删除消息
 * @param canInviteUsers 是否可邀请用户
 */
@Serializable
data class ChatMember(
    val status: String,
    val user: User,
    @SerialName("until_date") val untilDate: UnixTime? = null,
    @SerialName("is_member") val isMember: Boolean? = null,
    @SerialName("can_restrict_members") val canRestrictMembers: Boolean? = null,
    @SerialName("can_delete_messages") val canDeleteMessages: Boolean? = null,
    @SerialName("can_invite_users") val canInviteUsers: Boolean? = null,
)
