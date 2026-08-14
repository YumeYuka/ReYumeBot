package app

import VerificationPayload
import api.telegram.TelegramBotClient
import api.telegram.client.*
import api.telegram.core.Message
import api.telegram.permission.ChatPermissions
import api.telegram.request.ReplyKeyboardRemove
import common.logger
import moe.yumeyuka.yumebot.common.nowMillis

class VerificationService(private val pendingJoinRequestRepository: PendingJoinRequestRepository) {
    private val logger = logger<VerificationService>()

    suspend fun handleWebAppData(
        botClient: TelegramBotClient,
        message: Message,
        data: String,
    ) {
        val fromUser = message.from ?: return
        val payload =
            runCatching {
                botClient.decode<VerificationPayload>(data)
            }
                .onFailure { error ->
                    logger.warn("Invalid web_app_data from userId=${fromUser.id}: ${error.message}")
                }
                .getOrNull() ?: return

        if (!payload.isVerifiedEvent()) {
            logger.warn(
                "Unexpected web_app_data event from userId=${fromUser.id}: ${payload.event ?: payload.type}"
            )
            return
        }

        val pendingJoinRequest = pendingJoinRequestRepository.findByUserId(fromUser.id)
        if (pendingJoinRequest == null) {
            deleteTelegramMessage(botClient, message.chat.id, message.messageId)
            sendSelfDeletingStatusMessage(botClient, message.chat.id, "没有找到待审批的入群申请。")
            return
        }

        if (pendingJoinRequest.expiresAtMillis?.let { it <= nowMillis() } == true) {
            pendingJoinRequestRepository.removeByUserId(fromUser.id)
            cleanupPendingMessages(botClient, pendingJoinRequest)
            deleteTelegramMessage(botClient, message.chat.id, message.messageId)
            rejectTimedOutRequest(botClient, pendingJoinRequest)
            sendSelfDeletingStatusMessage(botClient, message.chat.id, "验证已超时，请重新申请加入后重试。")
            logger.info(
                "Expired verification rejected: userId=${pendingJoinRequest.userId}, testOnly=${pendingJoinRequest.testOnly}"
            )
            return
        }

        if (pendingJoinRequest.needsUnmuteOnSuccess) {
            val currentMember =
                runCatching {
                    botClient.getChatMember(pendingJoinRequest.chatId, pendingJoinRequest.userId)
                }.getOrNull()

            if (currentMember?.status == "kicked") {
                logger.warn(
                    "User was banned by admin during verification, cannot unmute: chatId=${pendingJoinRequest.chatId}, userId=${pendingJoinRequest.userId}"
                )
                pendingJoinRequestRepository.removeByUserId(fromUser.id)
                cleanupPendingMessages(botClient, pendingJoinRequest)
                deleteTelegramMessage(botClient, message.chat.id, message.messageId)
                sendSelfDeletingStatusMessage(botClient, message.chat.id, "您已被管理员封禁，无法解除限制。")
                return
            }

            botClient.restrictChatMember(
                chatId = pendingJoinRequest.chatId,
                userId = pendingJoinRequest.userId,
                permissions = unmutedPermissions(),
                untilDate = 0,
            )
            logger.info(
                "New member verification passed: chatId=${pendingJoinRequest.chatId}, userId=${pendingJoinRequest.userId}, github=${payload.githubUsername}"
            )
        } else if (!pendingJoinRequest.testOnly) {
            botClient.approveChatJoinRequest(
                chatId = pendingJoinRequest.chatId,
                userId = pendingJoinRequest.userId,
            )
            pendingJoinRequestRepository.markApproved(pendingJoinRequest.userId)
            logger.info(
                "Join request approved: chatId=${pendingJoinRequest.chatId}, userId=${pendingJoinRequest.userId}, github=${payload.githubUsername}"
            )
        } else {
            logger.info(
                "Verification test passed: chatId=${pendingJoinRequest.chatId}, userId=${pendingJoinRequest.userId}, github=${payload.githubUsername}"
            )
        }
        pendingJoinRequestRepository.removeByUserId(fromUser.id)
        cleanupPendingMessages(botClient, pendingJoinRequest)
        deleteTelegramMessage(botClient, message.chat.id, message.messageId)
        val statusMessage =
            botClient.sendMessage(
                chatId = message.chat.id,
                text =
                    if (pendingJoinRequest.testOnly) {
                        "验证测试通过。"
                    } else if (pendingJoinRequest.needsUnmuteOnSuccess) {
                        "验证通过，已解除群内发言限制。"
                    } else {
                        "验证通过，已同意入群。"
                    },
                replyMarkup = ReplyKeyboardRemove(),
            )
        if (pendingJoinRequest.needsUnmuteOnSuccess) {
            deleteTelegramMessage(botClient, message.chat.id, statusMessage.messageId)
        }
        logger.info(
            "Verification completed: userId=${pendingJoinRequest.userId}, github=${payload.githubUsername}, testOnly=${pendingJoinRequest.testOnly}"
        )
    }

    suspend fun manualApproveMember(
        botClient: TelegramBotClient,
        chatId: Long,
        targetUserId: Long,
        adminId: Long?,
    ): Boolean {
        val currentMember =
            runCatching {
                botClient.getChatMember(chatId, targetUserId)
            }.getOrNull()

        if (currentMember?.status == "kicked") {
            logger.warn(
                "Cannot manually approve user who is already kicked/banned: chatId=$chatId, userId=$targetUserId"
            )
            return false
        }

        botClient.restrictChatMember(
            chatId = chatId,
            userId = targetUserId,
            permissions = unmutedPermissions(),
            untilDate = 0,
        )

        val pending = pendingJoinRequestRepository.findByUserId(targetUserId)
        if (pending != null) {
            if (!pending.needsUnmuteOnSuccess && !pending.testOnly) {
                runCatching {
                    botClient.approveChatJoinRequest(chatId, targetUserId)
                }
                pendingJoinRequestRepository.markApproved(targetUserId)
            }
            cleanupPendingMessages(botClient, pending)
            pendingJoinRequestRepository.removeByUserId(targetUserId)
        }

        logger.info(
            "Member manually approved by admin: chatId=$chatId, userId=$targetUserId, adminId=$adminId"
        )
        return true
    }

    private suspend fun cleanupPendingMessages(
        botClient: TelegramBotClient,
        pendingJoinRequest: PendingJoinRequest,
    ) {
        pendingJoinRequest.promptMessageId?.let {
            deleteTelegramMessage(botClient, pendingJoinRequest.promptChatId, it)
        }
        val guideChatId = pendingJoinRequest.guideChatId
        val guideMessageId = pendingJoinRequest.guideMessageId
        if (guideChatId != null && guideMessageId != null) {
            deleteTelegramMessage(botClient, guideChatId, guideMessageId)
        }
    }

    private suspend fun rejectTimedOutRequest(
        botClient: TelegramBotClient,
        pendingJoinRequest: PendingJoinRequest,
    ) {
        if (pendingJoinRequest.testOnly) return
        if (pendingJoinRequest.needsUnmuteOnSuccess) {
            val currentMember =
                runCatching {
                    botClient.getChatMember(pendingJoinRequest.chatId, pendingJoinRequest.userId)
                }.getOrNull()

            if (currentMember?.status == "kicked") {
                logger.info(
                    "User was already banned by admin during verification, skipping unban: chatId=${pendingJoinRequest.chatId}, userId=${pendingJoinRequest.userId}"
                )
                return
            }

            if (currentMember?.status != "left") {
                botClient.banChatMember(
                    chatId = pendingJoinRequest.chatId,
                    userId = pendingJoinRequest.userId,
                    untilDate = 0,
                    revokeMessages = false,
                )
                botClient.unbanChatMember(
                    chatId = pendingJoinRequest.chatId,
                    userId = pendingJoinRequest.userId,
                    onlyIfBanned = true,
                )
            }
        } else {
            runCatching {
                botClient.declineChatJoinRequest(
                    chatId = pendingJoinRequest.chatId,
                    userId = pendingJoinRequest.userId,
                )
            }
                .onFailure { error ->
                    logger.warn(
                        "Decline join request failed: chatId=${pendingJoinRequest.chatId}, userId=${pendingJoinRequest.userId}, error=${error.message}"
                    )
                }
        }
    }

    internal fun unmutedPermissions(): ChatPermissions =
        ChatPermissions(
            canSendMessages = true,
            canSendAudios = true,
            canSendDocuments = true,
            canSendPhotos = true,
            canSendVideos = true,
            canSendVideoNotes = true,
            canSendVoiceNotes = true,
            canSendPolls = true,
            canSendOtherMessages = true,
            canAddWebPagePreviews = true,
            canInviteUsers = true,
            canEditTag = true,
        )

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
                    "Delete message failed: chatId=$chatId, messageId=$messageId, error=${error.message}"
                )
            }
    }

    private suspend fun sendSelfDeletingStatusMessage(
        botClient: TelegramBotClient,
        chatId: Long,
        text: String,
    ) {
        val statusMessage =
            botClient.sendMessage(
                chatId = chatId,
                text = text,
                replyMarkup = ReplyKeyboardRemove(),
            )
        deleteTelegramMessage(botClient, chatId, statusMessage.messageId)
    }
}
