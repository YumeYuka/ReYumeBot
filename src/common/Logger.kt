package common

private fun defaultLogFormatter(event: LogEvent): String = "[${event.level.name}] ${event.message}"

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR;

    fun allows(target: LogLevel): Boolean = target.ordinal >= ordinal
}

data class LogEvent(
    val level: LogLevel,
    val message: String,
)

fun interface LogFormatter {
    fun format(event: LogEvent): String
}

fun interface LogSink {
    fun write(formatted: String)
}

data class LoggerConfig(
    val minLevel: LogLevel = LogLevel.INFO,
    val formatter: LogFormatter = LogFormatter(::defaultLogFormatter),
    val sink: LogSink = LogSink(::println),
)

interface Logger {
    fun log(level: LogLevel, message: String)

    fun log(level: LogLevel, message: () -> String)

    fun debug(message: String): Unit = log(LogLevel.DEBUG, message)

    fun debug(message: () -> String): Unit = log(LogLevel.DEBUG, message)

    fun info(message: String): Unit = log(LogLevel.INFO, message)

    fun info(message: () -> String): Unit = log(LogLevel.INFO, message)

    fun warn(message: String): Unit = log(LogLevel.WARN, message)

    fun warn(message: () -> String): Unit = log(LogLevel.WARN, message)

    fun error(message: String): Unit = log(LogLevel.ERROR, message)

    fun error(message: () -> String): Unit = log(LogLevel.ERROR, message)
}

abstract class BaseLogger(private val config: LoggerConfig = LoggerConfig()) : Logger {
    override fun log(level: LogLevel, message: String) {
        if (config.minLevel.allows(level)) {
            writeLog(level, message)
        }
    }

    override fun log(level: LogLevel, message: () -> String) {
        if (config.minLevel.allows(level)) {
            writeLog(level, message())
        }
    }

    private fun writeLog(level: LogLevel, message: String) {
        val event = LogEvent(level, message)
        config.sink.write(config.formatter.format(event))
    }
}

object LogManager {
    private var config = LoggerConfig()
    private val cache = mutableMapOf<String, Logger>()

    fun configure(config: LoggerConfig) {
        this.config = config
        cache.clear()
    }

    fun get(tag: String): Logger =
        cache.getOrPut(tag) {
            ConsoleLogger(config)
        }
}

fun logger(tag: String): Logger = LogManager.get(tag)

inline fun <reified T : Any> logger(): Logger = LogManager.get(T::class.simpleName ?: "Anonymous")

fun Any.logger(): Logger = LogManager.get(this::class.simpleName ?: "Anonymous")
