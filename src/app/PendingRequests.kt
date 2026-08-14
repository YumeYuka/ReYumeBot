package app

import api.telegram.common.TelegramId
import api.telegram.core.User

data class PendingJoinRequest(
    val chatId: TelegramId,
    val userId: TelegramId,
    val userChatId: TelegramId,
    val promptChatId: TelegramId = userChatId,
    val promptMessageId: Long? = null,
    val guideChatId: TelegramId? = null,
    val guideMessageId: Long? = null,
    val expiresAtMillis: Long? = null,
    val needsUnmuteOnSuccess: Boolean = false,
    val testOnly: Boolean = false,
)

class PendingJoinRequestRepository {
    private val pendingJoinRequests = mutableMapOf<TelegramId, PendingJoinRequest>()
    private val verificationTestChatIds = mutableMapOf<TelegramId, TelegramId>()
    private val promptCooldownUntilMillis = mutableMapOf<TelegramId, Long>()
    private val recentlyApprovedUserIds = mutableSetOf<TelegramId>()
    private val usernameToUserId = mutableMapOf<String, TelegramId>()

    fun recordUser(user: User) {
        user.username?.let { username ->
            usernameToUserId[username.lowercase().removePrefix("@")] = user.id
        }
    }

    fun findUserIdByUsername(username: String): TelegramId? =
        usernameToUserId[username.lowercase().removePrefix("@")]

    fun markApproved(userId: TelegramId) {
        recentlyApprovedUserIds.add(userId)
    }

    fun consumeApproved(userId: TelegramId): Boolean = recentlyApprovedUserIds.remove(userId)

    fun save(pendingJoinRequest: PendingJoinRequest) {
        pendingJoinRequests[pendingJoinRequest.userId] = pendingJoinRequest
    }

    fun findByUserId(userId: TelegramId): PendingJoinRequest? = pendingJoinRequests[userId]

    fun removeByUserId(userId: TelegramId): PendingJoinRequest? = pendingJoinRequests.remove(userId)

    fun removeExpired(nowMillis: Long): List<PendingJoinRequest> {
        val expired =
            pendingJoinRequests.values.filter { pending ->
                pending.expiresAtMillis?.let { it <= nowMillis } == true
            }
        expired.forEach { pendingJoinRequests.remove(it.userId) }
        return expired
    }

    fun savePrivateGuideMessage(
        userId: TelegramId,
        chatId: TelegramId,
        messageId: Long,
    ) {
        pendingJoinRequests[userId]?.let { pending ->
            pendingJoinRequests[userId] =
                pending.copy(
                    userChatId = chatId,
                    guideChatId = chatId,
                    guideMessageId = messageId,
                )
        }
    }

    fun startPromptCooldown(userId: TelegramId, untilMillis: Long) {
        promptCooldownUntilMillis[userId] = untilMillis
    }

    fun isPromptCooldownActive(userId: TelegramId, nowMillis: Long): Boolean {
        val untilMillis = promptCooldownUntilMillis[userId] ?: return false
        if (untilMillis <= nowMillis) {
            promptCooldownUntilMillis.remove(userId)
            return false
        }
        return true
    }

    fun saveVerificationTestChatId(userId: TelegramId, chatId: TelegramId) {
        verificationTestChatIds[userId] = chatId
    }

    fun findVerificationTestChatId(userId: TelegramId): TelegramId? =
        verificationTestChatIds[userId]
}
