package moe.yumeyuka.yumebot.common

import kotlin.time.Clock

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
