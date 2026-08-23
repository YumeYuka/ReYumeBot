package api.telegram.client

import api.telegram.TelegramBotClient
import api.telegram.common.TelegramId
import api.telegram.core.Message
import api.telegram.request.AnswerCallbackQueryRequest
import api.telegram.request.DeleteMessageRequest
import api.telegram.request.ReplyMarkup
import api.telegram.request.SendMessageRequest
import api.telegram.request.SendPhotoRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.yumeyuka.yumebot.common.nowMillis
import platform.posix.getcwd

suspend fun TelegramBotClient.sendMessage(request: SendMessageRequest): Message = execute("sendMessage", request)

suspend fun TelegramBotClient.sendPhoto(request: SendPhotoRequest): Message = execute("sendPhoto", request)

suspend fun TelegramBotClient.sendVideoFile(
    chatId: TelegramId,
    filePath: String,
    caption: String? = null,
    durationSeconds: Int? = null,
    parseMode: String? = null,
    messageThreadId: Long? = null,
) {
    val videoPath = Path(filePath)
    require(SystemFileSystem.exists(videoPath)) { "视频文件不存在：$filePath" }
    val fileName = videoPath.name

    val textFields =
        buildMap {
            put("chat_id", chatId.toString())
            messageThreadId?.let { put("message_thread_id", it.toString()) }
            durationSeconds?.let { put("duration", it.toString()) }
            parseMode?.let { put("parse_mode", it) }
            if (!caption.isNullOrBlank()) {
                put("caption", caption)
            }
            put("supports_streaming", "true")
        }

    deliverMedia(
        method = "sendVideo",
        fileFieldName = "video",
        filePath = filePath,
        fileName = fileName,
        fileContentType = ContentType.Video.MP4.toString(),
        textFields = textFields,
    )
}

suspend fun TelegramBotClient.sendAudioFile(
    chatId: TelegramId,
    filePath: String,
    title: String? = null,
    performer: String? = null,
    caption: String? = null,
    durationSeconds: Int? = null,
    parseMode: String? = null,
    messageThreadId: Long? = null,
) {
    val audioPath = Path(filePath)
    require(SystemFileSystem.exists(audioPath)) { "音频文件不存在：$filePath" }
    val fileName = audioPath.name

    val boundary = "----ReYumeBotBoundary${nowMillis()}"
    val textFields =
        buildMap {
            put("chat_id", chatId.toString())
            messageThreadId?.let { put("message_thread_id", it.toString()) }
            durationSeconds?.let { put("duration", it.toString()) }
            title?.let { put("title", it) }
            performer?.let { put("performer", it) }
            parseMode?.let { put("parse_mode", it) }
            if (!caption.isNullOrBlank()) {
                put("caption", caption)
            }
        }

    val contentType =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }

    deliverMedia(
        method = "sendAudio",
        fileFieldName = "audio",
        filePath = filePath,
        fileName = fileName,
        fileContentType = contentType,
        textFields = textFields,
    )
}

/**
 * 统一发送媒体文件：本地 Bot API Server 模式直接传绝对路径（服务器自行读盘上传，零内存占用，上限 2GB）；
 * 官方 API 模式走 multipart 上传（文件整体进内存，上限 50MB）。
 */
private suspend fun TelegramBotClient.deliverMedia(
    method: String,
    fileFieldName: String,
    filePath: String,
    fileName: String,
    fileContentType: String,
    textFields: Map<String, String>,
) {
    if (isLocalBotApiServer) {
        val body =
            buildJsonObject {
                textFields.forEach { (name, value) -> put(name, value) }
                put(fileFieldName, resolveAbsolutePath(filePath))
            }
        execute(method, body, Message.serializer())
        return
    }

    val fileBytes = SystemFileSystem.source(Path(filePath)).buffered().use { it.readByteArray() }
    val boundary = "----ReYumeBotBoundary${nowMillis()}"
    val multipartBytes =
        buildMultipartBody(
            boundary = boundary,
            textFields = textFields,
            fileFieldName = fileFieldName,
            fileName = fileName,
            fileContentType = fileContentType,
            fileBytes = fileBytes,
        )

    val response =
        multipartHttpClient.post("$apiBaseUrl/$method") {
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", boundary))
            setBody(multipartBytes)
        }
    val responseBody = response.bodyAsText()
    require(response.status.value in 200..299) { "Telegram 媒体上传 HTTP 失败：${response.status.value} $responseBody" }
    val telegramResponse = decodeTelegramResponse(responseBody, Message.serializer())
    require(telegramResponse.ok && telegramResponse.result != null) {
        "Telegram 媒体上传失败：${telegramResponse.errorCode} ${telegramResponse.description}"
    }
}

/** 本地 Bot API Server 要求绝对路径；相对路径基于进程工作目录解析。 */
@OptIn(ExperimentalForeignApi::class)
private fun resolveAbsolutePath(path: String): String {
    if (path.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path)) return path
    val cwd = getcwd(null, 0.convert())?.toKString()?.replace('\\', '/')?.trimEnd('/') ?: return path
    return "$cwd/$path"
}

private fun buildMultipartBody(
    boundary: String,
    textFields: Map<String, String>,
    fileFieldName: String,
    fileName: String,
    fileContentType: String,
    fileBytes: ByteArray,
): ByteArray {
    val buffer = Buffer()
    for ((name, value) in textFields) {
        buffer.write("--$boundary\r\n".encodeToByteArray())
        buffer.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".encodeToByteArray())
        buffer.write(value.encodeToByteArray())
        buffer.write("\r\n".encodeToByteArray())
    }
    buffer.write("--$boundary\r\n".encodeToByteArray())
    buffer.write("Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"\r\n".encodeToByteArray())
    buffer.write("Content-Type: $fileContentType\r\n\r\n".encodeToByteArray())
    buffer.write(fileBytes)
    buffer.write("\r\n".encodeToByteArray())
    buffer.write("--$boundary--\r\n".encodeToByteArray())
    return buffer.readByteArray()
}

suspend fun TelegramBotClient.deleteMessage(request: DeleteMessageRequest): Boolean = execute("deleteMessage", request)

suspend fun TelegramBotClient.deleteMessage(
    chatId: TelegramId,
    messageId: Long,
): Boolean =
    deleteMessage(
        DeleteMessageRequest(
            chatId = chatId,
            messageId = messageId,
        ),
    )

suspend fun TelegramBotClient.answerCallbackQuery(request: AnswerCallbackQueryRequest): Boolean = execute("answerCallbackQuery", request)

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
        ),
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
        ),
    )
