package api.telegram.client

import api.telegram.TelegramBotClient
import api.telegram.update.GetUpdatesRequest
import api.telegram.update.Update

suspend fun TelegramBotClient.getUpdates(
    request: GetUpdatesRequest = GetUpdatesRequest()
): List<Update> = execute("getUpdates", request)

suspend fun TelegramBotClient.getUpdates(
    offset: Long? = null,
    limit: Int? = 100,
    timeout: Int? = 30,
    allowedUpdates: List<String> = GetUpdatesRequest().allowedUpdates,
): List<Update> =
    getUpdates(
        GetUpdatesRequest(
            offset = offset,
            limit = limit,
            timeout = timeout,
            allowedUpdates = allowedUpdates,
        )
    )

fun TelegramBotClient.decodeUpdate(payload: String): Update = decode(payload)
