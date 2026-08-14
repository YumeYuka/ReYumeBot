package api.telegram.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Long Polling 拉取更新请求。
 *
 * @param offset 下一次要拉取的 updateId，通常为 lastUpdateId + 1
 * @param limit 单次拉取数量，1-100
 * @param timeout Long Polling 超时时间，单位秒
 * @param allowedUpdates 只接收指定类型更新
 */
@Serializable
data class GetUpdatesRequest(
    val offset: Long? = null,
    val limit: Int? = 100,
    val timeout: Int? = 30,
    @SerialName("allowed_updates")
    val allowedUpdates: List<String> =
        listOf(
            "message",
            "chat_member",
            "chat_join_request",
            "callback_query",
        ),
)
