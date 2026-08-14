package api.telegram.core

import api.telegram.common.UnixTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Telegram 消息实体。
 */
@Serializable
data class MessageEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String? = null,
    val user: User? = null,
    val language: String? = null,
    @SerialName("custom_emoji_id") val customEmojiId: String? = null,
)

/**
 * Telegram 消息对象。
 *
 * 这里只保留入群验证、踢人、删消息常用字段。
 *
 * @param messageId 消息 ID
 * @param messageThreadId 论坛话题 ID
 * @param from 发送者
 * @param date 发送时间
 * @param chat 所属聊天
 * @param text 文本内容
 * @param entities 消息实体
 * @param replyToMessage 被回复的消息
 * @param webAppData Mini App 通过 Telegram.WebApp.sendData 回传的数据
 * @param newChatMembers 新入群成员
 * @param leftChatMember 离开群的成员
 */
@Serializable
data class Message(
    @SerialName("message_id") val messageId: Long,
    @SerialName("message_thread_id") val messageThreadId: Long? = null,
    val from: User? = null,
    val date: UnixTime,
    val chat: Chat,
    val text: String? = null,
    val entities: List<MessageEntity>? = null,
    @SerialName("reply_to_message") val replyToMessage: Message? = null,
    @SerialName("web_app_data") val webAppData: WebAppData? = null,
    @SerialName("new_chat_members") val newChatMembers: List<User>? = null,
    @SerialName("left_chat_member") val leftChatMember: User? = null,
)

/**
 * Mini App 回传数据。
 *
 * @param data 前端 sendData 发送的字符串
 * @param buttonText 打开 Mini App 的按钮文本
 */
@Serializable
data class WebAppData(
    val data: String,
    @SerialName("button_text") val buttonText: String,
)
