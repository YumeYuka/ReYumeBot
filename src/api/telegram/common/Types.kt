package api.telegram.common

/**
 * Telegram 用户 ID、群组 ID、频道 ID。
 *
 * Telegram 官方说明：部分 ID 可能超过 32 位整数范围， Kotlin 中统一使用 Long。
 */
typealias TelegramId = Long

/** Unix 时间戳，单位：秒。 */
typealias UnixTime = Long
