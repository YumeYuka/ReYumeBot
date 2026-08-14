package api.telegram.join

import api.telegram.common.UnixTime
import api.telegram.core.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Telegram 群邀请链接。
 *
 * @param inviteLink 邀请链接
 * @param creator 创建者
 * @param createsJoinRequest 是否需要管理员审批
 * @param isPrimary 是否主链接
 * @param isRevoked 是否已撤销
 * @param name 链接名称
 * @param expireDate 过期时间
 * @param memberLimit 成员数量限制
 * @param pendingJoinRequestCount 待审批申请数量
 */
@Serializable
data class ChatInviteLink(
    @SerialName("invite_link") val inviteLink: String,
    val creator: User,
    @SerialName("creates_join_request") val createsJoinRequest: Boolean,
    @SerialName("is_primary") val isPrimary: Boolean,
    @SerialName("is_revoked") val isRevoked: Boolean,
    val name: String? = null,
    @SerialName("expire_date") val expireDate: UnixTime? = null,
    @SerialName("member_limit") val memberLimit: Int? = null,
    @SerialName("pending_join_request_count") val pendingJoinRequestCount: Int? = null,
)
