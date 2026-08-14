package api.telegram.client

import api.telegram.TelegramBotClient
import api.telegram.common.TelegramId
import api.telegram.member.ChatMember
import api.telegram.permission.ChatPermissions
import api.telegram.request.BanChatMemberRequest
import api.telegram.request.GetChatMemberRequest
import api.telegram.request.RestrictChatMemberRequest
import api.telegram.request.UnbanChatMemberRequest

suspend fun TelegramBotClient.getChatMember(request: GetChatMemberRequest): ChatMember =
    execute("getChatMember", request)

suspend fun TelegramBotClient.getChatMember(
    chatId: TelegramId,
    userId: TelegramId,
): ChatMember =
    getChatMember(
        GetChatMemberRequest(
            chatId = chatId,
            userId = userId,
        )
    )

suspend fun TelegramBotClient.banChatMember(request: BanChatMemberRequest): Boolean =
    execute("banChatMember", request)

suspend fun TelegramBotClient.banChatMember(
    chatId: TelegramId,
    userId: TelegramId,
    untilDate: Long? = null,
    revokeMessages: Boolean? = true,
): Boolean =
    banChatMember(
        BanChatMemberRequest(
            chatId = chatId,
            userId = userId,
            untilDate = untilDate,
            revokeMessages = revokeMessages,
        )
    )

suspend fun TelegramBotClient.unbanChatMember(request: UnbanChatMemberRequest): Boolean =
    execute("unbanChatMember", request)

suspend fun TelegramBotClient.unbanChatMember(
    chatId: TelegramId,
    userId: TelegramId,
    onlyIfBanned: Boolean? = true,
): Boolean =
    unbanChatMember(
        UnbanChatMemberRequest(
            chatId = chatId,
            userId = userId,
            onlyIfBanned = onlyIfBanned,
        )
    )

suspend fun TelegramBotClient.restrictChatMember(request: RestrictChatMemberRequest): Boolean =
    execute("restrictChatMember", request)

suspend fun TelegramBotClient.restrictChatMember(
    chatId: TelegramId,
    userId: TelegramId,
    permissions: ChatPermissions,
    untilDate: Long? = null,
): Boolean =
    restrictChatMember(
        RestrictChatMemberRequest(
            chatId = chatId,
            userId = userId,
            permissions = permissions,
            untilDate = untilDate,
        )
    )
