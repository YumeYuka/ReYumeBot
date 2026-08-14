package app

import api.telegram.TelegramBotClient
import api.telegram.client.BotCommand
import api.telegram.client.getMe
import api.telegram.client.setMyCommands
import common.logger

class Daemon {
    private val logger = logger<Daemon>()

    suspend fun run() {
        logger.info("Daemon is running")
        val config =
            getConfig().also {
                logger.info("Config loaded: miniAppUrl=${it.miniAppUrl}")
            }

        TelegramBotClient(config.botToken).use { botClient ->
            val botUsername = botClient.getMe().username
            logger.info("Bot username: ${botUsername ?: "unknown"}")

            runCatching {
                botClient.setMyCommands(
                    listOf(
                        BotCommand("start", "获取验证引导"),
                        BotCommand("ban", "封禁违规用户 (仅管理员)"),
                        BotCommand("pass", "直接通过成员验证 (仅管理员)"),
                    )
                )
                logger.info("Bot commands registered successfully")
            }.onFailure { error ->
                logger.warn("Register bot commands failed: ${error.message}")
            }

            val pendingJoinRequestRepository = PendingJoinRequestRepository()
            val markupFactory = TelegramMarkupFactory(config, botUsername)
            val verificationService = VerificationService(pendingJoinRequestRepository)
            val updateHandler =
                BotUpdateHandler(
                    pendingJoinRequestRepository = pendingJoinRequestRepository,
                    commandHandler =
                        CommandHandler(
                            pendingJoinRequestRepository = pendingJoinRequestRepository,
                            verificationService = verificationService,
                            markupFactory = markupFactory,
                        ),
                    joinRequestService =
                        JoinRequestService(pendingJoinRequestRepository, markupFactory),
                    verificationService = verificationService,
                    newMemberVerificationService =
                        NewMemberVerificationService(
                            pendingJoinRequestRepository,
                            markupFactory,
                        ),
                )
            BotUpdateLoop(botClient, config, updateHandler).listen()
        }
    }
}
