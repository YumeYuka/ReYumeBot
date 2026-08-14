package app

import api.telegram.TelegramBotClient
import api.telegram.client.banChatMember
import api.telegram.client.deleteMessage
import api.telegram.client.getChatMember
import api.telegram.client.sendMessage
import api.telegram.core.Message
import common.logger

private const val PERMANENT_BAN_UNTIL_DATE = 0L

class CommandHandler(
    private val pendingJoinRequestRepository: PendingJoinRequestRepository,
    private val verificationService: VerificationService,
    private val markupFactory: TelegramMarkupFactory,
) {
    private val logger = logger<CommandHandler>()

    suspend fun handleMessage(
        botClient: TelegramBotClient,
        config: Config,
        message: Message,
    ) {
        val text = message.text?.trim() ?: return
        when {
            text.startsWith("/start") -> sendStartGuide(botClient, config, message)
            text.startsWith("/ban") -> handleBanCommand(botClient, message)
            text.startsWith("/通过") || text.startsWith("/pass") || text.startsWith("/approve") ->
                handlePassCommand(botClient, message)
        }
    }

    private suspend fun sendStartGuide(
        botClient: TelegramBotClient,
        config: Config,
        message: Message,
    ) {
        val fromUser = message.from
        if (message.chat.type != "private") {
            if (fromUser != null) {
                pendingJoinRequestRepository.saveVerificationTestChatId(
                    fromUser.id,
                    message.chat.id,
                )
            }
            botClient.sendMessage(
                chatId = message.chat.id,
                text = "请点击下方按钮前往机器人私聊页面完成验证测试。",
                messageThreadId = message.messageThreadId,
                replyMarkup = markupFactory.inlineGuideMarkup(),
            )
            return
        }

        if (config.miniAppUrl.isNullOrBlank()) {
            botClient.sendMessage(message.chat.id, "验证入口尚未配置。")
            return
        }

        val pendingJoinRequest = fromUser?.let { pendingJoinRequestRepository.findByUserId(it.id) }

        val guideMessage =
            botClient.sendMessage(
                chatId = message.chat.id,
                text = "请点击下方按钮打开验证页面，完成验证后会自动解除群内发言限制。",
                replyMarkup = markupFactory.verificationKeyboard(),
            )

        if (fromUser != null) {
            if (pendingJoinRequest != null) {
                deletePreviousPrivateGuide(botClient, pendingJoinRequest, message.chat.id)
                pendingJoinRequestRepository.savePrivateGuideMessage(
                    userId = fromUser.id,
                    chatId = message.chat.id,
                    messageId = guideMessage.messageId,
                )
            } else {
                pendingJoinRequestRepository.save(
                    PendingJoinRequest(
                        chatId =
                            pendingJoinRequestRepository.findVerificationTestChatId(fromUser.id)
                                ?: message.chat.id,
                        userId = fromUser.id,
                        userChatId = message.chat.id,
                        promptMessageId = guideMessage.messageId,
                        testOnly = true,
                    )
                )
            }
        }
    }

    private suspend fun deletePreviousPrivateGuide(
        botClient: TelegramBotClient,
        pendingJoinRequest: PendingJoinRequest,
        privateChatId: Long,
    ) {
        val previousMessageIds = buildSet {
            if (pendingJoinRequest.guideChatId == privateChatId) {
                pendingJoinRequest.guideMessageId?.let(::add)
            }
            if (pendingJoinRequest.testOnly && pendingJoinRequest.promptChatId == privateChatId) {
                pendingJoinRequest.promptMessageId?.let(::add)
            }
        }

        previousMessageIds.forEach { messageId ->
            runCatching {
                botClient.deleteMessage(privateChatId, messageId)
            }
                .onFailure { error ->
                    logger.warn(
                        "Delete old verification guide failed: chatId=$privateChatId, messageId=$messageId, error=${error.message}"
                    )
                }
        }
    }

    private suspend fun handleBanCommand(
        botClient: TelegramBotClient,
        message: Message,
    ) {
        if (!canRestrictMembers(botClient, message)) {
            botClient.sendMessage(
                chatId = message.chat.id,
                text = "只有拥有封禁成员权限的管理员可以使用 /ban。",
                messageThreadId = message.messageThreadId,
            )
            logger.warn(
                "Ban command denied: chatId=${message.chat.id}, commandMessageId=${message.messageId}, fromUserId=${message.from?.id}"
            )
            return
        }

        val text = message.text?.trim() ?: ""
        val banTargetMessage = message.replyToMessage
        var banTargetUserId = banTargetMessage?.from?.id
        val banTargetMessageId = banTargetMessage?.messageId

        if (banTargetUserId == null) {
            val textMentionUser = message.entities?.firstOrNull { it.type == "text_mention" }?.user
            if (textMentionUser != null) {
                banTargetUserId = textMentionUser.id
            }
        }

        if (banTargetUserId == null) {
            val mentionEntity = message.entities?.firstOrNull { it.type == "mention" }
            val mentionedUsername =
                if (mentionEntity != null && text.length >= mentionEntity.offset + mentionEntity.length) {
                    text.substring(mentionEntity.offset, mentionEntity.offset + mentionEntity.length)
                        .removePrefix("@")
                } else {
                    text.split("\\s+".toRegex()).drop(1).firstOrNull()?.removePrefix("@")
                }

            if (!mentionedUsername.isNullOrBlank()) {
                val directId = mentionedUsername.toLongOrNull()
                if (directId != null) {
                    banTargetUserId = directId
                } else {
                    banTargetUserId =
                        pendingJoinRequestRepository.findUserIdByUsername(mentionedUsername)
                    if (banTargetUserId == null) {
                        botClient.sendMessage(
                            chatId = message.chat.id,
                            text = "未在缓存中找到 @$mentionedUsername 的用户 ID。请尝试回复该用户的消息发送 /ban，或直接使用数字 ID：/ban <用户ID>。",
                            messageThreadId = message.messageThreadId,
                        )
                        return
                    }
                }
            }
        }

        if (banTargetUserId == null) {
            botClient.sendMessage(
                chatId = message.chat.id,
                text = "用法：回复目标用户的消息发送 /ban，或直接指定用户：/ban @用户名 或 /ban <用户ID>。",
                messageThreadId = message.messageThreadId,
            )
            return
        }

        val success =
            runCatching {
                botClient.banChatMember(
                    chatId = message.chat.id,
                    userId = banTargetUserId,
                    untilDate = PERMANENT_BAN_UNTIL_DATE,
                    revokeMessages = true,
                )
            }
                .onFailure { error ->
                    logger.error(
                        "Ban member failed: chatId=${message.chat.id}, userId=$banTargetUserId, error=${error.message}"
                    )
                    botClient.sendMessage(
                        chatId = message.chat.id,
                        text = "封禁失败：${error.message}",
                        messageThreadId = message.messageThreadId,
                    )
                }
                .getOrDefault(false)

        if (success) {
            logger.info(
                "User banned: chatId=${message.chat.id}, userId=$banTargetUserId, commandMessageId=${message.messageId}"
            )
            deleteBanMessages(botClient, message, banTargetMessageId)
        }
    }

    private suspend fun handlePassCommand(
        botClient: TelegramBotClient,
        message: Message,
    ) {
        if (!canRestrictMembers(botClient, message)) {
            botClient.sendMessage(
                chatId = message.chat.id,
                text = "只有拥有封禁/限制成员权限的管理员可以使用 /通过。",
                messageThreadId = message.messageThreadId,
            )
            logger.warn(
                "Pass command denied: chatId=${message.chat.id}, commandMessageId=${message.messageId}, fromUserId=${message.from?.id}"
            )
            return
        }

        val text = message.text?.trim() ?: ""
        val targetMessage = message.replyToMessage
        var targetUserId = targetMessage?.from?.id

        if (targetUserId == null) {
            val textMentionUser = message.entities?.firstOrNull { it.type == "text_mention" }?.user
            if (textMentionUser != null) {
                targetUserId = textMentionUser.id
            }
        }

        if (targetUserId == null) {
            val mentionEntity = message.entities?.firstOrNull { it.type == "mention" }
            val mentionedUsername =
                if (mentionEntity != null && text.length >= mentionEntity.offset + mentionEntity.length) {
                    text.substring(mentionEntity.offset, mentionEntity.offset + mentionEntity.length)
                        .removePrefix("@")
                } else {
                    text.split("\\s+".toRegex()).drop(1).firstOrNull()?.removePrefix("@")
                }

            if (!mentionedUsername.isNullOrBlank()) {
                val directId = mentionedUsername.toLongOrNull()
                if (directId != null) {
                    targetUserId = directId
                } else {
                    targetUserId =
                        pendingJoinRequestRepository.findUserIdByUsername(mentionedUsername)
                    if (targetUserId == null) {
                        botClient.sendMessage(
                            chatId = message.chat.id,
                            text = "未在缓存中找到 @$mentionedUsername 的用户 ID。请尝试回复该用户的消息发送 /通过，或直接使用数字 ID：/通过 <用户ID>。",
                            messageThreadId = message.messageThreadId,
                        )
                        return
                    }
                }
            }
        }

        if (targetUserId == null) {
            botClient.sendMessage(
                chatId = message.chat.id,
                text = "用法：回复目标用户的消息发送 /通过，或直接指定用户：/通过 @用户名 或 /通过 <用户ID>。",
                messageThreadId = message.messageThreadId,
            )
            return
        }

        val success =
            verificationService.manualApproveMember(
                botClient = botClient,
                chatId = message.chat.id,
                targetUserId = targetUserId,
                adminId = message.from?.id,
            )

        if (success) {
            logger.info(
                "User approved via command: chatId=${message.chat.id}, userId=$targetUserId, commandMessageId=${message.messageId}"
            )
            runCatching {
                botClient.deleteMessage(message.chat.id, message.messageId)
            }
        } else {
            botClient.sendMessage(
                chatId = message.chat.id,
                text = "通过验证失败，该用户已被管理员封禁或无法操作。",
                messageThreadId = message.messageThreadId,
            )
        }
    }

    private suspend fun deleteBanMessages(
        botClient: TelegramBotClient,
        commandMessage: Message,
        banTargetMessageId: Long?,
    ) {
        val messageIds = buildList {
            add(commandMessage.messageId)
            if (banTargetMessageId != null) {
                add(banTargetMessageId)
            }
        }
        messageIds.forEach { messageId ->
            runCatching {
                botClient.deleteMessage(commandMessage.chat.id, messageId)
            }
                .onFailure { error ->
                    logger.warn(
                        "Delete ban message failed: chatId=${commandMessage.chat.id}, messageId=$messageId, error=${error.message}"
                    )
                }
        }
    }

    private suspend fun canRestrictMembers(
        botClient: TelegramBotClient,
        message: Message,
    ): Boolean {
        val operatorUserId = message.from?.id ?: return false
        val chatMember =
            runCatching {
                botClient.getChatMember(message.chat.id, operatorUserId)
            }
                .onFailure { error ->
                    logger.warn(
                        "Check ban permission failed: chatId=${message.chat.id}, userId=$operatorUserId, error=${error.message}"
                    )
                }
                .getOrNull() ?: return false

        return chatMember.status == "creator" ||
            (chatMember.status == "administrator" && chatMember.canRestrictMembers == true)
    }
}
