package app

import api.telegram.TelegramBotClient
import api.telegram.client.sendMessage
import api.telegram.client.sendPhoto
import api.telegram.client.sendVideoFile
import api.telegram.core.Message
import api.telegram.request.SendPhotoRequest
import bilibili.BilibiliLoginStatus
import bilibili.BilibiliService
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BilibiliMessageHandler(
    private val bilibiliService: BilibiliService,
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun handleMessage(botClient: TelegramBotClient, config: Config, message: Message): Boolean {
        val text = message.text?.trim().orEmpty()
        if (text.startsWith("/bili_login")) {
            startLogin(botClient, config, message)
            return true
        }
        val videoUrl = bilibiliService.extractVideoUrl(text) ?: return false
        downloadAndSend(botClient, message, videoUrl)
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
        }
    }

    private suspend fun downloadAndSend(botClient: TelegramBotClient, message: Message, videoUrl: String) {
        val downloadedVideo = runCatching { bilibiliService.downloadVideo(videoUrl) }.getOrElse { error ->
            botClient.sendMessage(message.chat.id, "B站视频处理失败：${error.message}", messageThreadId = message.messageThreadId)
            return
        }
        try {
            botClient.sendVideoFile(
                chatId = message.chat.id,
                filePath = downloadedVideo.filePath,
                caption = downloadedVideo.title,
                messageThreadId = message.messageThreadId,
            )
        } finally {
            bilibiliService.deleteDownloadedFile(downloadedVideo.filePath)
        }
    }
}