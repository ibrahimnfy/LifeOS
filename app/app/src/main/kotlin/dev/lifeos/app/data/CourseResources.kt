package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- course resources from the syllabus (#128, #160; docs/bridge.md)

/**
 * One resource the computer's syllabus run proposed for a course: downloaded into the course's
 * folder on the computer ([status] "downloaded"), or only listed, with a [reason] word.
 * Everything here is outside text (from the run and the web): shown as plain text only.
 */
@Serializable
data class CourseResource(
    val title: String = "",
    val url: String = "",
    /** "open_access_book", "vendor_documentation", "public_material", "paid" or "other". */
    val kind: String = "",
    val licence: String? = null,
    @SerialName("why_legal") val whyLegal: String? = null,
    val reference: String? = null,
    val status: String = "",
    val file: String? = null,
    val reason: String? = null,
) {
    val downloaded: Boolean get() = status == "downloaded"
}

/**
 * `GET /courses/{id}/resources`. [foldersNamed] is false until the owner has named their
 * existing resource folders on the computer: until then nothing is downloaded.
 */
@Serializable
data class CourseResourceView(
    @SerialName("course_id") val courseId: Long = 0,
    val items: List<CourseResource> = emptyList(),
    val running: Boolean = false,
    val pending: Boolean = false,
    @SerialName("folders_named") val foldersNamed: Boolean = true,
    @SerialName("last_run") val lastRun: String? = null,
    @SerialName("last_result") val lastResult: String? = null,
)

object CourseResources {
    /** Why an item was only listed, as the app words it. */
    enum class Reason {
        /** A paid book: listed with its link, never fetched. */
        PAID,

        /** Not a book or document the computer fetches (kind "other"). */
        OTHER,

        /** Nothing is downloaded until the owner has named their existing resource folders on the computer (#160). */
        FOLDERS_NOT_NAMED,

        /** The owner already has this file, in their resource folders or the course's folder (#160). */
        ALREADY_PRESENT,

        /** Not an HTTPS address. */
        NOT_HTTPS,

        /** A private or local address, or one that is not a usable address at all. */
        NOT_PUBLIC,

        /** What came back was not a PDF or EPUB. */
        NOT_A_DOCUMENT,

        /** Larger than the computer's limit per file. */
        TOO_LARGE,

        /** The download failed, or the host could not be reached; tried again at the next run. */
        FAILED,

        /** Beyond this run's number of downloads; tried again at the next run. */
        LATER,

        /** The audit log could not be written, so nothing was kept; tried again. */
        UNRECORDED,

        /** A word this app does not know yet. */
        UNKNOWN,
    }

    fun endpoint(courseId: Long) =
        Endpoint("course-$courseId-resources", "/courses/$courseId/resources", serializer = CourseResourceView.serializer())

    /** Why [item] is only listed; null for a downloaded one. */
    fun reasonOf(item: CourseResource): Reason? = if (item.downloaded) {
        null
    } else {
        when (item.reason) {
            "paid" -> Reason.PAID
            "other" -> Reason.OTHER
            "folders_not_named" -> Reason.FOLDERS_NOT_NAMED
            "already_present" -> Reason.ALREADY_PRESENT
            "not_https" -> Reason.NOT_HTTPS
            "private_address", "bad_address" -> Reason.NOT_PUBLIC
            "not_a_document" -> Reason.NOT_A_DOCUMENT
            "too_large" -> Reason.TOO_LARGE
            "download_failed", "unreachable", "host_not_allowed" -> Reason.FAILED
            "later" -> Reason.LATER
            "audit_unavailable" -> Reason.UNRECORDED
            else -> Reason.UNKNOWN
        }
    }
}
