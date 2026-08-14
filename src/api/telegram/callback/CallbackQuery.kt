package api.telegram.callback

import api.telegram.core.Message
import api.telegram.core.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Inline Keyboard 按钮回调。
 *
 * 可用于入群验证按钮，例如： approve:{chatId}:{userId} decline:{chatId}:{userId}
 *
 * @param id 回调 ID
 * @param from 点击按钮的用户
 * @param message 按钮所在消息
 * @param inlineMessageId inline 模式消息 ID
 * @param chatInstance 聊天全局标识
 * @param data 按钮 callback_data
 */
@Serializable
data class CallbackQuery(
    val id: String,
    val from: User,
    val message: Message? = null,
    @SerialName("inline_message_id") val inlineMessageId: String? = null,
    @SerialName("chat_instance") val chatInstance: String,
    val data: String? = null,
)
