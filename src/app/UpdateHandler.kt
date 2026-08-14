package app

import api.telegram.TelegramBotClient
import api.telegram.callback.CallbackQuery
import api.telegram.client.answerCallbackQuery
import api.telegram.client.deleteMessage
import api.telegram.client.getChatMember
import api.telegram.update.Update

class BotUpdateHandler(
    private val pendingJoinRequestRepository: PendingJoinRequestRepository,
    private val commandHandler: CommandHandler,
    private val joinRequestService: JoinRequestService,
    private val verificationService: VerificationService,
    private val newMemberVerificationService: NewMemberVerificationService,
) {
    suspend fun expirePendingVerifications(botClient: TelegramBotClient) {
        newMemberVerificationService.expirePendingVerifications(botClient)
    }

    suspend fun handleUpdate(
        botClient: TelegramBotClient,
        config: Config,
        update: Update,
    ) {
        update.chatJoinRequest?.let { joinRequest ->
            pendingJoinRequestRepository.recordUser(joinRequest.from)
            joinRequestService.handleChatJoinRequest(botClient, joinRequest)
            return
        }

        update.chatMember?.let { memberUpdated ->
            pendingJoinRequestRepository.recordUser(memberUpdated.from)
            pendingJoinRequestRepository.recordUser(memberUpdated.newChatMember.user)
        }

        update.myChatMember?.let { memberUpdated ->
            pendingJoinRequestRepository.recordUser(memberUpdated.from)
        }

        update.callbackQuery?.let { callbackQuery ->
            handleCallbackQuery(botClient, callbackQuery)
            return
        }

        update.message?.let { message ->
            message.from?.let { pendingJoinRequestRepository.recordUser(it) }
            message.replyToMessage?.from?.let { pendingJoinRequestRepository.recordUser(it) }
            message.newChatMembers?.forEach { pendingJoinRequestRepository.recordUser(it) }
            message.leftChatMember?.let { pendingJoinRequestRepository.recordUser(it) }

            if (!message.newChatMembers.isNullOrEmpty()) {
                newMemberVerificationService.handleNewChatMembers(botClient, message)
                return
            }

            if (message.leftChatMember != null) {
                newMemberVerificationService.handleLeftChatMember(botClient, message)
                return
            }

            val webAppData = message.webAppData
            if (webAppData != null) {
                verificationService.handleWebAppData(botClient, message, webAppData.data)
                return
            }

            commandHandler.handleMessage(botClient, config, message)
        }
    }

    private suspend fun handleCallbackQuery(
        botClient: TelegramBotClient,
        callbackQuery: CallbackQuery,
    ) {
        val data = callbackQuery.data ?: return
        val message = callbackQuery.message ?: return

        if (data.startsWith("admin_pass:")) {
            val targetUserId = data.removePrefix("admin_pass:").toLongOrNull()
            if (targetUserId == null) {
                botClient.answerCallbackQuery(callbackQuery.id, text = "无效的用户 ID")
                return
            }

            val operatorUserId = callbackQuery.from.id
            val chatMember =
                runCatching {
                    botClient.getChatMember(message.chat.id, operatorUserId)
                }.getOrNull()

            val isAdmin =
                chatMember?.status == "creator" ||
                    (chatMember?.status == "administrator" && chatMember.canRestrictMembers == true)

            if (!isAdmin) {
                botClient.answerCallbackQuery(
                    callbackQueryId = callbackQuery.id,
                    text = "只有拥有封禁/限制权限的管理员才能直接通过。",
                    showAlert = true,
                )
                return
            }

            botClient.answerCallbackQuery(
                callbackQueryId = callbackQuery.id,
                text = "已直接通过该成员验证。",
            )

            val success =
                verificationService.manualApproveMember(
                    botClient = botClient,
                    chatId = message.chat.id,
                    targetUserId = targetUserId,
                    adminId = operatorUserId,
                )

            if (success) {
                runCatching {
                    botClient.deleteMessage(message.chat.id, message.messageId)
                }
            }
        }
    }
}
