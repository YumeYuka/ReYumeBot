package app

import api.telegram.TelegramBotClient
import api.telegram.client.deleteMessage
import api.telegram.client.sendAudioFile
import api.telegram.client.sendMessage
import api.telegram.core.Message
import common.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import netease.NeteaseDownloadedSong
import netease.NeteaseService

class NeteaseMessageHandler(
    private val neteaseService: NeteaseService,
    private val botUsername: String?,
) {
    private val logger = logger<NeteaseMessageHandler>()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun handleMessage(
        botClient: TelegramBotClient,
        message: Message,
    ): Boolean {
        val text = message.text?.trim().orEmpty()
        val songUrl = neteaseService.extractSongUrl(text) ?: return false
        val progressMessage =
            botClient.sendMessage(
                chatId = message.chat.id,
                text = "正在解析并下载网易云音乐，请稍候。",
                messageThreadId = message.messageThreadId,
            )
        backgroundScope.launch {
            downloadAndSend(botClient, message, songUrl, progressMessage)
        }
        return true
    }

    private suspend fun downloadAndSend(
        botClient: TelegramBotClient,
        message: Message,
        songUrl: String,
        progressMessage: Message,
    ) {
        logger.info("Netease audio job started: chatId=${message.chat.id}")
        val song =
            runCatching { neteaseService.downloadSong(songUrl) }.getOrElse { error ->
                logger.warn("Netease audio job failed before upload: chatId=${message.chat.id}, error=${error.message}")
                botClient.sendMessage(
                    chatId = message.chat.id,
                    text = "网易云音乐处理失败：${error.message}\n原链接仍保留在聊天中：$songUrl",
                    messageThreadId = message.messageThreadId,
                )
                runCatching { botClient.deleteMessage(message.chat.id, progressMessage.messageId) }
                return
            }

        try {
            logger.info("Netease audio upload started: chatId=${message.chat.id}")
            botClient.sendAudioFile(
                chatId = message.chat.id,
                filePath = song.filePath,
                title = song.name,
                performer = song.artists,
                caption = formatSongCaption(song),
                durationSeconds = song.durationSeconds,
                parseMode = "HTML",
                messageThreadId = message.messageThreadId,
            )
            logger.info("Netease audio job completed: chatId=${message.chat.id}")
            runCatching {
                botClient.deleteMessage(message.chat.id, progressMessage.messageId)
            }.onFailure { error ->
                logger.warn("Netease progress message deletion failed: ${error.message}")
            }
        } catch (error: Exception) {
            logger.warn("Netease audio upload failed: chatId=${message.chat.id}, error=${error.message}")
            botClient.sendMessage(
                chatId = message.chat.id,
                text = "网易云音乐已下载，但发送到 Telegram 失败：${error.message}",
                messageThreadId = message.messageThreadId,
            )
        } finally {
            neteaseService.deleteDownloadedFile(song.filePath)
        }
    }

    private fun formatSongCaption(song: NeteaseDownloadedSong): String {
        val nameLink = "<a href=\"${escapeHtmlAttribute(song.trackUrl)}\">${escapeHtml(song.name)}</a>"
        val albumLine = if (song.album.isNotBlank()) "专辑：${escapeHtml(song.album)}\n" else ""
        val infoParts =
            buildList {
                add(formatFileSize(song.sizeBytes))
                if (song.bitrateKbps > 0) add("${song.bitrateKbps}kbps")
                add(song.format.uppercase())
            }
        val viaLine = botUsername?.let { "\nvia @$it" }.orEmpty()
        return "<b>「$nameLink」- ${escapeHtml(song.artists)}</b>\n" +
            "$albumLine<blockquote>${infoParts.joinToString(" · ")}\n#网易云音乐 #${escapeHtml(song.level)}</blockquote>$viaLine"
    }

    private fun formatFileSize(bytes: Long): String =
        when {
            bytes >= 1024 * 1024 -> {
                val tenths = (bytes * 10) / (1024 * 1024)
                "${tenths / 10}.${tenths % 10}MB"
            }
            bytes > 0 -> "${bytes / 1024}KB"
            else -> "大小未知"
        }

    private fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun escapeHtmlAttribute(text: String): String = escapeHtml(text).replace("\"", "&quot;")
}
