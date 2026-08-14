package app

import api.telegram.TelegramBotClient
import api.telegram.client.getUpdates
import api.telegram.update.Update
import common.logger
import kotlinx.coroutines.delay

private const val POLLING_TIMEOUT_SECONDS = 30
private const val TRANSIENT_ERROR_BACKOFF_MILLIS = 1_000L
private val TRANSIENT_TELEGRAM_ERROR_MESSAGES =
    listOf(
        "server prematurely closed the connection",
        "Not enough data available",
    )

class BotUpdateLoop(
    private val botClient: TelegramBotClient,
    private val config: Config,
    private val updateHandler: BotUpdateHandler,
) {
    private val logger = logger<BotUpdateLoop>()

    suspend fun listen() {
        var nextOffset: Long? = null

        while (true) {
            expirePendingVerifications()

            val updates = runCatching {
                botClient.getUpdates(
                    offset = nextOffset,
                    timeout = POLLING_TIMEOUT_SECONDS,
                )
            }
                .onFailure { error ->
                    if (error.isTransientTelegramConnectionError()) {
                        logger.warn("Transient getUpdates failure: ${error.message}; retrying")
                    } else {
                        logger.error("getUpdates failed: ${error.message}")
                    }
                    delay(TRANSIENT_ERROR_BACKOFF_MILLIS)
                }
                .getOrElse {
                    emptyList()
                }

            for (update in updates) {
                if (handleUpdate(update)) {
                    nextOffset = update.updateId + 1
                } else {
                    delay(TRANSIENT_ERROR_BACKOFF_MILLIS)
                    break
                }
            }
        }
    }

    private suspend fun handleUpdate(update: Update): Boolean = runCatching {
        updateHandler.handleUpdate(botClient, config, update)
        true
    }
        .onFailure { error ->
            if (error.isTransientTelegramConnectionError()) {
                logger.warn(
                    "Transient Telegram API failure while handling update ${update.updateId}: ${error.message}; will retry without advancing offset"
                )
            } else {
                logger.error("Update ${update.updateId} failed: ${error.message}")
            }
        }
        .getOrElse { error ->
            !error.isTransientTelegramConnectionError()
        }

    private suspend fun expirePendingVerifications() {
        runCatching {
            updateHandler.expirePendingVerifications(botClient)
        }
            .onFailure { error ->
                if (error.isTransientTelegramConnectionError()) {
                    logger.warn(
                        "Transient Telegram API failure while expiring verifications: ${error.message}"
                    )
                } else {
                    logger.error("Expire pending verifications failed: ${error.message}")
                }
            }
    }
}

private fun Throwable.isTransientTelegramConnectionError(): Boolean =
    generateSequence(this) { it.cause }
        .mapNotNull { it.message }
        .any { message ->
            TRANSIENT_TELEGRAM_ERROR_MESSAGES.any { transientMessage ->
                message.contains(transientMessage, ignoreCase = true)
            }
        }
