package api.telegram.client

import api.telegram.TelegramBotClient
import api.telegram.common.TelegramId
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import api.telegram.core.Message
import api.telegram.request.AnswerCallbackQueryRequest
import api.telegram.request.DeleteMessageRequest
import api.telegram.request.ReplyMarkup
import api.telegram.request.SendMessageRequest
import api.telegram.request.SendPhotoRequest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

suspend fun TelegramBotClient.sendMessage(request: SendMessageRequest): Message =
    execute("sendMessage", request)

suspend fun TelegramBotClient.sendPhoto(request: SendPhotoRequest): Message =
    execute("sendPhoto", request)

suspend fun TelegramBotClient.sendVideoFile(
    chatId: TelegramId,
    filePath: String,
    caption: String,
    durationSeconds: Int? = null,
    parseMode: String? = null,
    messageThreadId: Long? = null,
): Unit {
    val videoPath = Path(filePath)
    require(SystemFileSystem.exists(videoPath)) { "视频文件不存在：$filePath" }
    val videoBytes = SystemFileSystem.source(videoPath).buffered().use { it.readByteArray() }
    val fileName = videoPath.name
    val response = multipartHttpClient.post("$apiBaseUrl/sendVideo") {
        setBody(
            MultiPartFormDataContent(
                formData {
                    append("chat_id", chatId.toString())
                    messageThreadId?.let { append("message_thread_id", it.toString()) }
                    durationSeconds?.let { append("duration", it.toString()) }
                    parseMode?.let { append("parse_mode", it) }
                    append("caption", caption)
                    append("supports_streaming", "true")
                    append(
                        "video",
                        videoBytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Video.MP4.toString())
                            append(HttpHeaders.ContentDisposition, ContentDisposition.File.withParameter(ContentDisposition.Parameters.FileName, fileName).toString())
                        },
                    )
                }
            )
        )
    }
    val responseBody = response.bodyAsText()
    require(response.status.value in 200..299) { "Telegram 视频上传 HTTP 失败：${response.status.value} $responseBody" }
    val telegramResponse = decodeTelegramResponse(responseBody, Message.serializer())
    require(telegramResponse.ok && telegramResponse.result != null) {
        "Telegram 视频上传失败：${telegramResponse.errorCode} ${telegramResponse.description}"
    }
}
suspend fun TelegramBotClient.deleteMessage(request: DeleteMessageRequest): Boolean =
    execute("deleteMessage", request)

suspend fun TelegramBotClient.deleteMessage(
    chatId: TelegramId,
    messageId: Long,
): Boolean =
    deleteMessage(
        DeleteMessageRequest(
            chatId = chatId,
            messageId = messageId,
        )
    )

suspend fun TelegramBotClient.answerCallbackQuery(request: AnswerCallbackQueryRequest): Boolean =
    execute("answerCallbackQuery", request)

suspend fun TelegramBotClient.answerCallbackQuery(
    callbackQueryId: String,
    text: String? = null,
    showAlert: Boolean? = null,
): Boolean =
    answerCallbackQuery(
        AnswerCallbackQueryRequest(
            callbackQueryId = callbackQueryId,
            text = text,
            showAlert = showAlert,
        )
    )

suspend fun TelegramBotClient.sendMessage(
    chatId: TelegramId,
    text: String,
    parseMode: String? = null,
    messageThreadId: Long? = null,
    disableWebPagePreview: Boolean = true,
    replyMarkup: ReplyMarkup? = null,
): Message =
    sendMessage(
        SendMessageRequest(
            chatId = chatId,
            text = text,
            parseMode = parseMode,
            messageThreadId = messageThreadId,
            disableWebPagePreview = disableWebPagePreview,
            replyMarkup = replyMarkup,
        )
    )
