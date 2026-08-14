package api.telegram.update

import api.telegram.callback.CallbackQuery
import api.telegram.core.Message
import api.telegram.join.ChatJoinRequest
import api.telegram.member.ChatMemberUpdated
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Telegram 推送更新对象。
 *
 * 同一个 Update 中最多只有一个可选字段存在。
 *
 * @param updateId 更新唯一 ID
 * @param message 普通消息
 * @param chatMember 群成员状态变化
 * @param myChatMember bot 自身成员状态变化
 * @param chatJoinRequest 入群申请
 * @param callbackQuery 按钮回调
 */
@Serializable
data class Update(
    @SerialName("update_id") val updateId: Long,
    val message: Message? = null,
    @SerialName("chat_member") val chatMember: ChatMemberUpdated? = null,
    @SerialName("my_chat_member") val myChatMember: ChatMemberUpdated? = null,
    @SerialName("chat_join_request") val chatJoinRequest: ChatJoinRequest? = null,
    @SerialName("callback_query") val callbackQuery: CallbackQuery? = null,
)
