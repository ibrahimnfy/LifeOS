package dev.lifeos.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's own line icons, drawn for this project on a 24 x 24 grid (the same
 * shapes as in docs/design/mockups). No third-party icon set is used.
 * Regular icons have a 1.5 dp stroke; [bold] gives the heavier active variant.
 */
object LifeIcons {
    private const val REGULAR = 1.5f
    private const val ACTIVE = 2.25f

    private fun icon(
        name: String,
        stroke: Float = REGULAR,
        solid: String? = null,
        line: String? = null,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        if (line != null) {
            addPath(
                pathData = addPathNodes(line),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        if (solid != null) {
            addPath(pathData = addPathNodes(solid), fill = SolidColor(Color.Black))
        }
    }.build()

    private const val TODAY =
        "M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0zM12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5" +
            "M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"
    private const val CHAT = "M5 5h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-8l-5 4v-4H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"
    private const val TASKS =
        "M7 4h10a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3zM8 12.2l2.8 2.8L16 9.2"
    private const val MORE =
        "M4.1 12a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0-2.8 0zM10.6 12a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0-2.8 0z " +
            "M17.1 12a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0-2.8 0z"
    private const val MORE_ACTIVE =
        "M3.7 12a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0zM10.2 12a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0z " +
            "M16.7 12a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0z"

    val Today = icon("Today", line = TODAY)
    val TodayActive = icon("TodayActive", ACTIVE, line = TODAY)
    val Chat = icon("Chat", line = CHAT)
    val ChatActive = icon("ChatActive", ACTIVE, line = CHAT)
    val Tasks = icon("Tasks", line = TASKS)
    val TasksActive = icon("TasksActive", ACTIVE, line = TASKS)
    val More = icon("More", solid = MORE)
    val MoreActive = icon("MoreActive", solid = MORE_ACTIVE)

    val Plus = icon("Plus", 2f, line = "M12 5v14M5 12h14")
    val Refresh = icon("Refresh", line = "M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4.5v4h-4")
    val Download = icon("Download", line = "M12 4v11M7 10.5l5 5 5-5M5 19.5h14")
    val Close = icon("Close", line = "M6 6l12 12M18 6L6 18")
    val Clock = icon("Clock", line = "M4 12a8 8 0 1 0 16 0a8 8 0 1 0-16 0zM12 8v4.5l2.5 1.5")
    val Link = icon("Link", line = "M10 6H6v12h12v-4M13 5h6v6M19 5l-8 8")
    val Back = icon("Back", line = "M15 5l-7 7 7 7")
    val Chevron = icon("Chevron", line = "M9 5l7 7-7 7")
    val Check = icon("Check", 2.5f, line = "M5 12.5l4.5 4.5L19 7.5")
    val Send = icon("Send", 2f, line = "M12 19V5M6 11l6-6 6 6")

    val Sessions = icon("Sessions", line = "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0-17 0zM12 7v5l3.2 2")
    val Notes = icon(
        "Notes",
        line = "M12 5H6.5A1.5 1.5 0 0 0 5 6.5v11A1.5 1.5 0 0 0 6.5 19h11a1.5 1.5 0 0 0 1.5-1.5V12" +
            "M17.6 3.6l2.8 2.8-8 8-3.4.6.6-3.4z",
    )
    val Projects = icon(
        "Projects",
        line = "M3.5 7a1.5 1.5 0 0 1 1.5-1.5h4l2 2.5h8A1.5 1.5 0 0 1 20.5 9.5v8A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z",
    )
    val Calendar = icon(
        "Calendar",
        line = "M6 5.5h12a1.5 1.5 0 0 1 1.5 1.5v11a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 18V7A1.5 1.5 0 0 1 6 5.5z" +
            "M4.5 10h15M8.5 3.5v4M15.5 3.5v4",
    )
    val Courses = icon(
        "Courses",
        line = "M12 6.5c-2-1.3-4.5-1.5-7.5-1v12.5c3-.5 5.5-.3 7.5 1 2-1.3 4.5-1.5 7.5-1V5.5c-3-.5-5.5-.3-7.5 1zM12 6.5V19",
    )
    val Activity = icon("Activity", line = "M5 19.5v-6M12 19.5v-14M19 19.5v-9")
    val Settings = icon(
        "Settings",
        line = "M4 7.5h9M17 7.5h3M4 16.5h3M11 16.5h9M13 7.5a2 2 0 1 0 4 0a2 2 0 1 0-4 0zM7 16.5a2 2 0 1 0 4 0a2 2 0 1 0-4 0z",
    )
}
