package api.telegram.client

import api.telegram.TelegramBotClient
import api.telegram.common.TelegramId
import api.telegram.join.ChatInviteLink
import api.telegram.request.ApproveChatJoinRequest
import api.telegram.request.CreateChatInviteLinkRequest
import api.telegram.request.DeclineChatJoinRequest

suspend fun TelegramBotClient.createChatInviteLink(
    request: CreateChatInviteLinkRequest
): ChatInviteLink = execute("createChatInviteLink", request)

suspend fun TelegramBotClient.createChatInviteLink(
    chatId: TelegramId,
    name: String? = null,
    expireDate: Long? = null,
    createsJoinRequest: Boolean = true,
): ChatInviteLink =
    createChatInviteLink(
        CreateChatInviteLinkRequest(
            chatId = chatId,
            name = name,
            expireDate = expireDate,
            createsJoinRequest = createsJoinRequest,
        )
    )

suspend fun TelegramBotClient.approveChatJoinRequest(request: ApproveChatJoinRequest): Boolean =
    execute("approveChatJoinRequest", request)

suspend fun TelegramBotClient.approveChatJoinRequest(
    chatId: TelegramId,
    userId: TelegramId,
): Boolean =
    approveChatJoinRequest(
        ApproveChatJoinRequest(
            chatId = chatId,
            userId = userId,
        )
    )

suspend fun TelegramBotClient.declineChatJoinRequest(request: DeclineChatJoinRequest): Boolean =
    execute("declineChatJoinRequest", request)

suspend fun TelegramBotClient.declineChatJoinRequest(
    chatId: TelegramId,
    userId: TelegramId,
): Boolean =
    declineChatJoinRequest(
        DeclineChatJoinRequest(
            chatId = chatId,
            userId = userId,
        )
    )
