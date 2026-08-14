package app

import api.telegram.TelegramBotClient
import api.telegram.client.sendMessage
import api.telegram.join.ChatJoinRequest
import common.logger
import moe.yumeyuka.yumebot.common.nowMillis

class JoinRequestService(
    private val pendingJoinRequestRepository: PendingJoinRequestRepository,
    private val markupFactory: TelegramMarkupFactory,
) {
    private val logger = logger<JoinRequestService>()

    suspend fun handleChatJoinRequest(
        botClient: TelegramBotClient,
        joinRequest: ChatJoinRequest,
    ) {
        logger.info(
            "Join request received: chatId=${joinRequest.chat.id}, userId=${joinRequest.from.id}, userChatId=${joinRequest.userChatId}"
        )
        val promptMessage =
            botClient.sendMessage(
                chatId = joinRequest.userChatId,
                text = buildJoinRequestPrompt(),
                replyMarkup = markupFactory.inlineGuideMarkup(),
            )

        pendingJoinRequestRepository.save(
            PendingJoinRequest(
                chatId = joinRequest.chat.id,
                userId = joinRequest.from.id,
                userChatId = joinRequest.userChatId,
                promptMessageId = promptMessage.messageId,
                expiresAtMillis = nowMillis() + VERIFICATION_TIMEOUT_MILLIS,
            )
        )
        logger.info(
            "Join request pending: chatId=${joinRequest.chat.id}, userId=${joinRequest.from.id}, promptMessageId=${promptMessage.messageId}"
        )
    }

    private fun buildJoinRequestPrompt(): String =
        "收到你的入群申请。\n请在 5 分钟内点击下方按钮前往机器人私聊页面，然后打开验证按钮完成验证，超时将拒绝申请。"
}
