package app

import api.telegram.request.*

class TelegramMarkupFactory(
    private val config: Config,
    private val botUsername: String?,
) {
    fun inlineGuideMarkup(targetUserId: Long? = null): InlineKeyboardMarkup? {
        if (botUsername.isNullOrBlank()) {
            return null
        }
        val encodedStartParam = "join"

        val guideButton =
            InlineKeyboardButton(
                text = "前往机器人验证",
                url = "https://t.me/$botUsername?start=$encodedStartParam",
            )

        if (targetUserId == null) {
            return InlineKeyboardMarkup(inlineKeyboard = listOf(listOf(guideButton)))
        }

        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "通过",
                            callbackData = "admin_pass:$targetUserId",
                        ),
                        InlineKeyboardButton(
                            text = "封禁",
                            callbackData = "admin_ban:$targetUserId",
                        ),
                    ),
                    listOf(guideButton),
                ),
        )
    }

    fun verificationKeyboard(): ReplyKeyboardMarkup? {
        val miniAppUrl = config.miniAppUrl
        if (miniAppUrl.isNullOrBlank()) {
            return null
        }

        return ReplyKeyboardMarkup(
            keyboard =
                listOf(
                    listOf(
                        KeyboardButton(
                            text = "打开验证页面",
                            webApp = WebAppInfo(miniAppUrl),
                        )
                    )
                )
        )
    }
}
