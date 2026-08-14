package api.telegram.request

import api.telegram.common.TelegramId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder

/**
 * 发送文本消息。
 *
 * @param chatId 目标会话 ID
 * @param text 消息文本
 * @param parseMode 文本解析模式
 * @param messageThreadId 论坛话题 ID
 * @param disableWebPagePreview 是否禁用链接预览
 * @param replyMarkup 回复或内联键盘
 */
@Serializable
data class SendMessageRequest(
    @SerialName("chat_id") val chatId: TelegramId,
    val text: String,
    @SerialName("parse_mode") val parseMode: String? = null,
    @SerialName("message_thread_id") val messageThreadId: Long? = null,
    @SerialName("disable_web_page_preview") val disableWebPagePreview: Boolean = true,
    @SerialName("reply_markup") val replyMarkup: ReplyMarkup? = null,
)

@Serializable(with = ReplyMarkupSerializer::class) sealed interface ReplyMarkup

object ReplyMarkupSerializer : kotlinx.serialization.KSerializer<ReplyMarkup> {
    override val descriptor: SerialDescriptor =
        kotlinx.serialization.json.JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ReplyMarkup) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("ReplyMarkup can only be encoded as JSON")
        val jsonElement =
            when (value) {
                is InlineKeyboardMarkup ->
                    jsonEncoder.json.encodeToJsonElement(InlineKeyboardMarkup.serializer(), value)
                is ReplyKeyboardRemove ->
                    jsonEncoder.json.encodeToJsonElement(ReplyKeyboardRemove.serializer(), value)
                is ReplyKeyboardMarkup ->
                    jsonEncoder.json.encodeToJsonElement(ReplyKeyboardMarkup.serializer(), value)
            }
        jsonEncoder.encodeJsonElement(jsonElement)
    }

    override fun deserialize(decoder: Decoder): ReplyMarkup {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("ReplyMarkup can only be decoded from JSON")
        val jsonObject =
            jsonDecoder.decodeJsonElement().let {
                it as? kotlinx.serialization.json.JsonObject
                    ?: throw SerializationException("ReplyMarkup must be a JSON object")
            }

        return when {
            "inline_keyboard" in jsonObject -> {
                jsonDecoder.json.decodeFromJsonElement(
                    InlineKeyboardMarkup.serializer(),
                    jsonObject,
                )
            }

            "remove_keyboard" in jsonObject -> {
                jsonDecoder.json.decodeFromJsonElement(ReplyKeyboardRemove.serializer(), jsonObject)
            }

            else -> {
                jsonDecoder.json.decodeFromJsonElement(ReplyKeyboardMarkup.serializer(), jsonObject)
            }
        }
    }
}

/**
 * 自定义回复键盘。
 *
 * @param keyboard 按钮行
 * @param resizeKeyboard 是否按按钮数量缩放键盘
 * @param oneTimeKeyboard 使用后是否隐藏键盘
 */
@Serializable
data class ReplyKeyboardMarkup(
    val keyboard: List<List<KeyboardButton>>,
    @SerialName("resize_keyboard") val resizeKeyboard: Boolean = true,
    @SerialName("one_time_keyboard") val oneTimeKeyboard: Boolean = true,
) : ReplyMarkup

/**
 * 移除自定义回复键盘。
 *
 * @param removeKeyboard 请求客户端移除当前回复键盘
 */
@Serializable
data class ReplyKeyboardRemove(@SerialName("remove_keyboard") val removeKeyboard: Boolean = true) :
    ReplyMarkup

/**
 * 回复键盘按钮。
 *
 * @param text 按钮文本
 * @param webApp Mini App 配置
 */
@Serializable
data class KeyboardButton(
    val text: String,
    @SerialName("web_app") val webApp: WebAppInfo? = null,
)

/**
 * Mini App 地址。
 *
 * @param url HTTPS Mini App URL
 */
@Serializable data class WebAppInfo(val url: String)

/**
 * 内联键盘。
 *
 * @param inlineKeyboard 按钮行
 */
@Serializable
data class InlineKeyboardMarkup(
    @SerialName("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>
) : ReplyMarkup

/**
 * 内联键盘按钮。
 *
 * @param text 按钮文本
 * @param url 点击后打开的 HTTPS 链接
 * @param callbackData 点击后回传给 Bot 的数据
 * @param webApp 点击后打开的 Mini App
 */
@Serializable
data class InlineKeyboardButton(
    val text: String,
    val url: String? = null,
    @SerialName("callback_data") val callbackData: String? = null,
    @SerialName("web_app") val webApp: WebAppInfo? = null,
)

/**
 * 删除消息。
 *
 * @param chatId 目标群 ID
 * @param messageId 消息 ID
 */
@Serializable
data class DeleteMessageRequest(
    @SerialName("chat_id") val chatId: TelegramId,
    @SerialName("message_id") val messageId: Long,
)

/**
 * 回复按钮回调。
 *
 * 点击 Inline Keyboard 后建议调用，避免客户端一直显示加载中。
 *
 * @param callbackQueryId 回调 ID
 * @param text 提示文本
 * @param showAlert 是否弹窗提示
 */
@Serializable
data class AnswerCallbackQueryRequest(
    @SerialName("callback_query_id") val callbackQueryId: String,
    val text: String? = null,
    @SerialName("show_alert") val showAlert: Boolean? = null,
)
