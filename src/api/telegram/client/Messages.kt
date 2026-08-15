package api.telegram.client

import api.telegram.TelegramBotClient
import api.telegram.common.TelegramId
import api.telegram.core.Message
import api.telegram.request.AnswerCallbackQueryRequest
import api.telegram.request.DeleteMessageRequest
import api.telegram.request.ReplyMarkup
import api.telegram.request.SendMessageRequest
import api.telegram.request.SendPhotoRequest
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.system

suspend fun TelegramBotClient.sendMessage(request: SendMessageRequest): Message =
    execute("sendMessage", request)

suspend fun TelegramBotClient.sendPhoto(request: SendPhotoRequest): Message =
    execute("sendPhoto", request)

suspend fun TelegramBotClient.sendVideoFile(
    chatId: TelegramId,
    filePath: String,
    caption: String,
    messageThreadId: Long? = null,
): Unit {
    val threadArgument = messageThreadId?.let { " -F ${shellQuote("message_thread_id=$it")}" }.orEmpty()
    val command =
        "curl --fail --silent --show-error --location " +
            "-F ${shellQuote("chat_id=$chatId")}$threadArgument " +
            "-F ${shellQuote("caption=$caption")} " +
            "-F ${shellQuote("supports_streaming=true")} " +
            "-F ${shellQuote("video=@$filePath")} " +
            shellQuote("$apiBaseUrl/sendVideo")
    require(system(command) == 0) { "Telegram 视频上传失败。" }
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

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"