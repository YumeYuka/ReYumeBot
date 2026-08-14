import app.Daemon
import common.LogLevel
import common.LogManager
import common.LoggerConfig
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    LogManager.configure(LoggerConfig(minLevel = LogLevel.INFO))
    Daemon().run()
}
