package api.telegram.request

import api.telegram.common.TelegramId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SendPhotoRequest(
    @SerialName("chat_id") val chatId: TelegramId,
    val photo: String,
    val caption: String? = null,
    @SerialName("message_thread_id") val messageThreadId: Long? = null,
)