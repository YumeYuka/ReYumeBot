package common

/** ConsoleLogger 的职责非常单一：把日志事件输出到控制台。 具体格式、最小级别、输出介质都由 LoggerConfig 统一配置。 */
class ConsoleLogger(config: LoggerConfig = LoggerConfig()) : BaseLogger(config = config)
