package app

import api.telegram.TelegramBotClient
import api.telegram.client.sendMessage
import api.telegram.client.sendPhoto
import api.telegram.client.sendVideoFile
import api.telegram.client.deleteMessage
import api.telegram.core.Message
import api.telegram.request.SendPhotoRequest
import bilibili.BilibiliDownloadedVideo
import bilibili.BilibiliLoginStatus
import bilibili.BilibiliService
import common.logger
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BilibiliMessageHandler(
    private val bilibiliService: BilibiliService,
) {
    private val logger = logger<BilibiliMessageHandler>()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun handleMessage(botClient: TelegramBotClient, config: Config, message: Message): Boolean {
        val text = message.text?.trim().orEmpty()
        if (text.startsWith("/bili_login")) {
            startLogin(botClient, config, message)
            return true
        }

        val videoUrl = bilibiliService.extractVideoUrl(text) ?: return false
        val progressMessage = botClient.sendMessage(
            chatId = message.chat.id,
            text = "正在解析并下载 B站视频，请稍候。",
            messageThreadId = message.messageThreadId,
        )
        backgroundScope.launch {
            downloadAndSend(botClient, message, videoUrl, progressMessage)
        }
        return true
    }

    private suspend fun startLogin(botClient: TelegramBotClient, config: Config, message: Message) {
        val operatorUserId = message.from?.id
        val configuredAdminId = config.bilibiliAdminId
        if (configuredAdminId == null || operatorUserId != configuredAdminId) {
            botClient.sendMessage(message.chat.id, "未配置或无权使用 B 站登录命令。", messageThreadId = message.messageThreadId)
            return
        }

        val qrCode = runCatching { bilibiliService.createLoginQrCode() }.getOrElse { error ->
            botClient.sendMessage(message.chat.id, "生成 B 站二维码失败：${error.message}", messageThreadId = message.messageThreadId)
            return
        }
        val qrCodeUrl = "https://api.qrserver.com/v1/create-qr-code/?size=400x400&data=${qrCode.loginUrl.encodeURLParameter()}"
        botClient.sendPhoto(SendPhotoRequest(message.chat.id, qrCodeUrl, "请使用哔哩哔哩手机客户端扫码登录。二维码有效期约三分钟。", message.messageThreadId))
        backgroundScope.launch {
            val status = runCatching { bilibiliService.waitForLogin(qrCode.qrCodeKey) }.getOrNull()
            val resultText = when (status) {
                BilibiliLoginStatus.SUCCESS -> "B站扫码登录成功，Cookie 已更新。"
                BilibiliLoginStatus.EXPIRED -> "B站二维码已过期，请重新发送 /bili_login。"
                BilibiliLoginStatus.TIMEOUT -> "B站扫码登录超时，请重新发送 /bili_login。"
                null -> "B站扫码登录失败，请重新发送 /bili_login。"
            }
            runCatching { botClient.sendMessage(message.chat.id, resultText, messageThreadId = message.messageThreadId) }
                .onFailure { error -> logger.warn("Bilibili login status notification failed: ${error.message}") }
        }
    }

    private suspend fun downloadAndSend(botClient: TelegramBotClient, message: Message, videoUrl: String, progressMessage: Message) {
        logger.info("Bilibili media job started: chatId=${message.chat.id}")
        val downloadedVideo = runCatching { bilibiliService.downloadVideo(videoUrl) }.getOrElse { error ->
            logger.warn("Bilibili media job failed before upload: chatId=${message.chat.id}, error=${error.message}")
            botClient.sendMessage(
                chatId = message.chat.id,
                text = "B站视频处理失败：${error.message}\n原链接仍保留在聊天中：$videoUrl",
                messageThreadId = message.messageThreadId,
            )
            return
        }

        try {

            logger.info("Bilibili media upload started: chatId=${message.chat.id}")
            botClient.sendVideoFile(
                chatId = message.chat.id,
                filePath = downloadedVideo.filePath,
                caption = "",
                durationSeconds = downloadedVideo.durationSeconds,
                messageThreadId = message.messageThreadId,
            )
            logger.info("Bilibili media job completed: chatId=${message.chat.id}")
            runCatching {
                botClient.deleteMessage(message.chat.id, progressMessage.messageId)
            }.onFailure { error ->
                logger.warn("Bilibili progress message deletion failed: ${error.message}")
            }
        } catch (error: Exception) {
            logger.warn("Bilibili media upload failed: chatId=${message.chat.id}, error=${error.message}")
            botClient.sendMessage(
                chatId = message.chat.id,
                text = "B站视频已下载，但发送到 Telegram 失败：${error.message}",
                messageThreadId = message.messageThreadId,
            )
        } finally {
            bilibiliService.deleteDownloadedFile(downloadedVideo.filePath)
        }
    }

    private fun formatVideoMetadata(downloadedVideo: BilibiliDownloadedVideo): String {
        val escapedTitle = escapeHtml(downloadedVideo.title)
        val escapedSummary = escapeHtml(downloadedVideo.summary)
        val escapedSourceUrl = escapeHtmlAttribute(downloadedVideo.sourceUrl)
        val summarySection = escapedSummary.ifBlank { "暂无简介。" }
        return "<b>$escapedTitle</b>\n\n$summarySection\n\n<a href=\"$escapedSourceUrl\">Source</a>"
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun escapeHtmlAttribute(text: String): String =
        escapeHtml(text).replace("\"", "&quot;")
}