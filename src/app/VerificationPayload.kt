import kotlinx.serialization.Serializable

private const val VERIFY_EVENT = "github_join_request_verified"
private const val QUIZ_VERIFY_EVENT = "quiz_verified"
private const val QUIZ_PASSING_SCORE = 70
private const val QUIZ_MAX_SCORE = 100
private val VERIFY_EVENTS =
    setOf(
        VERIFY_EVENT,
        "core_dev_links_verified",
        QUIZ_VERIFY_EVENT,
    )
private const val VERIFY_SCHEMA_VERSION = 1

@Serializable
data class VerificationPayload(
    val schemaVersion: Int? = null,
    val event: String? = null,
    val type: String? = null,
    val githubUsername: String? = null,
    val quizScore: Int? = null,
    val challenge: String? = null,
    val verifiedAt: String? = null,
    val telegramStartParam: String? = null,
    val telegramUserId: String? = null,
) {
    fun isVerifiedEvent(): Boolean =
        (schemaVersion == VERIFY_SCHEMA_VERSION &&
            event in VERIFY_EVENTS &&
            (event != QUIZ_VERIFY_EVENT || quizScore in QUIZ_PASSING_SCORE..QUIZ_MAX_SCORE)) ||
            type == "github_join_verification"
}
