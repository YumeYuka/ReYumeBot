package app

import api.telegram.TelegramBotClient
import api.telegram.client.*
import api.telegram.core.Message
import api.telegram.core.User
import api.telegram.permission.ChatPermissions
import common.logger
import moe.yumeyuka.yumebot.common.nowMillis

internal const val VERIFICATION_TIMEOUT_MILLIS = 5 * 60 * 1000L
private const val PROMPT_COOLDOWN_MILLIS = 60 * 1000L
private const val PERMANENT_BAN_UNTIL_DATE_FOR_TIMEOUT = 0L

class NewMemberVerificationService(
    private val pendingJoinRequestRepository: PendingJoinRequestRepository,
    private val markupFactory: TelegramMarkupFactory,
) {
    private val logger = logger<NewMemberVerificationService>()

    suspend fun handleNewChatMembers(
        botClient: TelegramBotClient,
        message: Message,
    ) {
        if (message.newChatMembers.isNullOrEmpty()) return
        deleteTelegramMessage(botClient, message.chat.id, message.messageId)

        val newMembers =
            message.newChatMembers
                .orEmpty()
                .filterNot { it.isBot }
                .filterNot { pendingJoinRequestRepository.consumeApproved(it.id) }
        if (newMembers.isEmpty()) return

        newMembers.forEach { member ->
            muteAndPrompt(botClient, message, member)
        }
    }

    suspend fun handleLeftChatMember(
        botClient: TelegramBotClient,
        message: Message,
    ) {
        deleteTelegramMessage(botClient, message.chat.id, message.messageId)
    }

    suspend fun expirePendingVerifications(botClient: TelegramBotClient) {
        val nowMillis = nowMillis()
        val expiredPending =
            pendingJoinRequestRepository.removeExpired(nowMillis).filterNot { it.testOnly }

        expiredPending.forEach { pending ->
            runCatching {
                cleanupPromptMessages(botClient, pending)
                if (pending.needsUnmuteOnSuccess) {
                    val currentMember =
                        runCatching {
                            botClient.getChatMember(pending.chatId, pending.userId)
                        }.getOrNull()

                    if (currentMember?.status == "kicked") {
                        logger.info(
                            "User was already banned by admin during verification, skipping unban: chatId=${pending.chatId}, userId=${pending.userId}"
                        )
                    } else if (currentMember?.status != "left") {
                        botClient.banChatMember(
                            chatId = pending.chatId,
                            userId = pending.userId,
                            untilDate = PERMANENT_BAN_UNTIL_DATE_FOR_TIMEOUT,
                            revokeMessages = false,
                        )
                        botClient.unbanChatMember(
                            chatId = pending.chatId,
                            userId = pending.userId,
                            onlyIfBanned = true,
                        )
                        logger.info(
                            "Verification timed out and user kicked: chatId=${pending.chatId}, userId=${pending.userId}"
                        )
                    }
                } else {
                    botClient.declineChatJoinRequest(
                        chatId = pending.chatId,
                        userId = pending.userId,
                    )
                    runCatching {
                        botClient.sendMessage(
                            chatId = pending.userChatId,
                            text = "验证超时，入群申请已被拒绝。如需加入请重新发起申请。",
                        )
                    }
                    logger.info(
                        "Verification timed out and join request declined: chatId=${pending.chatId}, userId=${pending.userId}"
                    )
                }
            }
                .onFailure { error ->
                    logger.warn(
                        "Expire verification failed: chatId=${pending.chatId}, userId=${pending.userId}, error=${error.message}"
                    )
                }
        }
    }

    private suspend fun muteAndPrompt(
        botClient: TelegramBotClient,
        message: Message,
        member: User,
    ) {
        val nowMillis = nowMillis()
        val expiresAtMillis = nowMillis + VERIFICATION_TIMEOUT_MILLIS

        botClient.restrictChatMember(
            chatId = message.chat.id,
            userId = member.id,
            permissions = mutedPermissions(),
            untilDate = 0,
        )

        val existingPending = pendingJoinRequestRepository.findByUserId(member.id)
        val reusablePromptMessageId =
            existingPending?.takeIf { it.chatId == message.chat.id }?.promptMessageId
        val promptMessageId =
            if (
                pendingJoinRequestRepository.isPromptCooldownActive(member.id, nowMillis) &&
                    reusablePromptMessageId != null
            ) {
                reusablePromptMessageId
            } else {
                val prompt =
                    botClient.sendMessage(
                        chatId = message.chat.id,
                        text = buildGroupPrompt(member),
                        parseMode = "HTML",
                        messageThreadId = message.messageThreadId,
                        replyMarkup = markupFactory.inlineGuideMarkup(member.id),
                    )
                pendingJoinRequestRepository.startPromptCooldown(
                    member.id,
                    nowMillis + PROMPT_COOLDOWN_MILLIS,
                )
                prompt.messageId
            }

        pendingJoinRequestRepository.save(
            PendingJoinRequest(
                chatId = message.chat.id,
                userId = member.id,
                userChatId = member.id,
                promptChatId = message.chat.id,
                promptMessageId = promptMessageId,
                guideChatId = existingPending?.guideChatId,
                guideMessageId = existingPending?.guideMessageId,
                expiresAtMillis = expiresAtMillis,
                needsUnmuteOnSuccess = true,
            )
        )
        logger.info(
            "New member muted for verification: chatId=${message.chat.id}, userId=${member.id}, expiresAtMillis=$expiresAtMillis"
        )
    }

    private fun mutedPermissions(): ChatPermissions =
        ChatPermissions(
            canSendMessages = false,
            canSendAudios = false,
            canSendDocuments = false,
            canSendPhotos = false,
            canSendVideos = false,
            canSendVideoNotes = false,
            canSendVoiceNotes = false,
            canSendPolls = false,
            canSendOtherMessages = false,
            canAddWebPagePreviews = false,
            canEditTag = false,
        )

    private fun buildGroupPrompt(member: User): String {
        // 有 username 用原生 @ 提及，没有则用 text mention 链接，两种方式都会通知本人
        val mention =
            member.username?.let { "@$it" }
                ?: "<a href=\"tg://user?id=${member.id}\">${escapeHtml(member.firstName)}</a>"
        return "$mention 欢迎加入。请在 5 分钟内点击下方按钮完成验证，超时会被移出群组。"
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private suspend fun cleanupPromptMessages(
        botClient: TelegramBotClient,
        pending: PendingJoinRequest,
    ) {
        pending.promptMessageId?.let { deleteTelegramMessage(botClient, pending.promptChatId, it) }
        val guideChatId = pending.guideChatId
        val guideMessageId = pending.guideMessageId
        if (guideChatId != null && guideMessageId != null) {
            deleteTelegramMessage(botClient, guideChatId, guideMessageId)
        }
    }

    private suspend fun deleteTelegramMessage(
        botClient: TelegramBotClient,
        chatId: Long,
        messageId: Long,
    ) {
        runCatching {
            botClient.deleteMessage(chatId, messageId)
        }
            .onFailure { error ->
                logger.warn(
                    "Delete verification prompt failed: chatId=$chatId, messageId=$messageId, error=${error.message}"
                )
            }
    }
}
