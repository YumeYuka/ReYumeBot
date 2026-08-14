package api.telegram.client

import api.telegram.TelegramBotClient
import api.telegram.core.User
import kotlinx.serialization.Serializable

@Serializable private data object EmptyRequest

@Serializable
data class BotCommand(
    val command: String,
    val description: String,
)

@Serializable
private data class SetMyCommandsRequest(
    val commands: List<BotCommand>,
)

suspend fun TelegramBotClient.getMe(): User = execute("getMe", EmptyRequest)

suspend fun TelegramBotClient.setMyCommands(commands: List<BotCommand>): Boolean =
    execute("setMyCommands", SetMyCommandsRequest(commands))

