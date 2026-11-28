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

    /** Scores (#206): a gauge, its needle pointing up and right. */
    private const val SCORES =
        "M5.1 17.5a8 8 0 1 1 13.8 0M12 14l3.4-4.6M10.6 14a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0-2.8 0z"

    val Today = icon("Today", line = TODAY)
    val TodayActive = icon("TodayActive", ACTIVE, line = TODAY)
    val Chat = icon("Chat", line = CHAT)
    val ChatActive = icon("ChatActive", ACTIVE, line = CHAT)
    val Tasks = icon("Tasks", line = TASKS)
    val TasksActive = icon("TasksActive", ACTIVE, line = TASKS)
    val Scores = icon("Scores", line = SCORES)
    val ScoresActive = icon("ScoresActive", ACTIVE, line = SCORES)
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
    val Copy = icon(
        "Copy",
        line = "M10.5 9h8a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5h-8A1.5 1.5 0 0 1 9 18.5v-8A1.5 1.5 0 0 1 10.5 9z " +
            "M6 15h-.5A1.5 1.5 0 0 1 4 13.5v-8A1.5 1.5 0 0 1 5.5 4h8A1.5 1.5 0 0 1 15 5.5V6",
    )
    val Send = icon("Send", 2f, line = "M12 19V5M6 11l6-6 6 6")

    val Sessions = icon("Sessions", line = "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0-17 0zM12 7v5l3.2 2")
    val Notes = icon(
        "Notes",
        line = "M12 5H6.5A1.5 1.5 0 0 0 5 6.5v11A1.5 1.5 0 0 0 6.5 19h11a1.5 1.5 0 0 0 1.5-1.5V12" +
            "M17.6 3.6l2.8 2.8-8 8-3.4.6.6-3.4z",
    )
    val Projects = icon("Projects", line = FOLDER)
    val Calendar = icon("Calendar", line = CALENDAR)
    val Courses = icon(
        "Courses",
        line = "M12 6.5c-2-1.3-4.5-1.5-7.5-1v12.5c3-.5 5.5-.3 7.5 1 2-1.3 4.5-1.5 7.5-1V5.5c-3-.5-5.5-.3-7.5 1zM12 6.5V19",
    )
    private const val SPARK = "M12 4l2.1 5.9L20 12l-5.9 2.1L12 20l-2.1-5.9L4 12l5.9-2.1z"
    private const val FILE = "M7 3.5h6.5L18 8v11a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5zM13.5 3.5V8H18"
    private const val FOLDER = "M3.5 7a1.5 1.5 0 0 1 1.5-1.5h4l2 2.5h8A1.5 1.5 0 0 1 20.5 9.5v8A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z"
    private const val CALENDAR =
        "M6 5.5h12a1.5 1.5 0 0 1 1.5 1.5v11a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 18V7A1.5 1.5 0 0 1 6 5.5z" +
            "M4.5 10h15M8.5 3.5v4M15.5 3.5v4"

    /** Section icons: in front of a card's heading. */
    val Spark = icon("Spark", line = SPARK)
    val File = icon("File", line = FILE)

    /**
     * Watermarks: the same shapes with a thin line, drawn large and faint in a card's corner
     * (purely decorative).
     */
    val SparkMark = icon("SparkMark", 0.8f, line = SPARK)
    val TodayMark = icon("TodayMark", 0.8f, line = TODAY)
    val TasksMark = icon("TasksMark", 0.8f, line = TASKS)

    // ---- the courses' own icons (one per course, see data/Looks.kt)

    val Wave = icon("Wave", line = "M2.5 12c1.6-5.5 3.2-5.5 4.75 0s3.15 5.5 4.75 0 3.2-5.5 4.75 0 3.15 5.5 4.75 0")
    val Chip = icon(
        "Chip",
        line = "M8.5 7h7A1.5 1.5 0 0 1 17 8.5v7a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 15.5v-7A1.5 1.5 0 0 1 8.5 7z" +
            " M10 7V4M14 7V4M10 20v-3M14 20v-3M7 10H4M7 14H4M20 10h-3M20 14h-3",
    )
    val Matrix = icon(
        "Matrix",
        line = "M7.5 4.5h-3v15h3M16.5 4.5h3v15h-3",
        solid = "M8.4 9a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0-2.2 0zM13.4 9a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0-2.2 0z" +
            " M8.4 15a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0-2.2 0zM13.4 15a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0-2.2 0z",
    )
    val Pen = icon("Pen", line = "M12 3.5l5.5 8.5L12 20.5 6.5 12zM12 20.5V13.5M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0z")
    val Dice = icon(
        "Dice",
        line = "M8 4.5h8A3.5 3.5 0 0 1 19.5 8v8a3.5 3.5 0 0 1-3.5 3.5H8A3.5 3.5 0 0 1 4.5 16V8A3.5 3.5 0 0 1 8 4.5z",
        solid = "M7.8 9a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0-2.4 0zM13.8 15a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0-2.4 0z" +
            " M10.8 12a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0-2.4 0z",
    )
    val Flask = icon(
        "Flask",
        line = "M9.5 3.5h5M10.5 3.5V9l-5.3 9a1.6 1.6 0 0 0 1.4 2.5h10.8a1.6 1.6 0 0 0 1.4-2.5L13.5 9V3.5M7.6 14.5h8.8",
    )
    val Folder = icon("Folder", line = FOLDER)

    // ---- chat and sessions

    /** "New chat": a sheet with a pen. */
    val Compose = icon(
        "Compose",
        line = "M12 5H6.5A1.5 1.5 0 0 0 5 6.5v11A1.5 1.5 0 0 0 6.5 19h11a1.5 1.5 0 0 0 1.5-1.5V12" +
            "M17.6 3.6l2.8 2.8-8 8-3.4.6.6-3.4z",
    )
    val Pin = icon("Pin", line = "M9 3.5h6l-1 6 3 3.5H7l3-3.5zM12 13v7.5")
    val Bell = icon("Bell", line = "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15zM10 20.5a2 2 0 0 0 4 0")
    val Search = icon("Search", line = "M4.5 10.5a6 6 0 1 0 12 0a6 6 0 1 0-12 0zM15 15l5 5")
    val Mic = icon("Mic", line = "M12 3.5a2.5 2.5 0 0 1 2.5 2.5v5a2.5 2.5 0 0 1-5 0V6A2.5 2.5 0 0 1 12 3.5zM6.5 11a5.5 5.5 0 0 0 11 0M12 16.5v4M9 20.5h6")

    // ---- the home's own icons (#195), paths of prototype C
    val Moon = icon("Moon", line = "M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z")
    val Terminal = icon(
        "Terminal",
        line = "M6 5h12a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1-2.5 2.5H6a2.5 2.5 0 0 1-2.5-2.5v-9A2.5 2.5 0 0 1 6 5zM7.5 10l3 2.5-3 2.5M12.5 15.5h4",
    )
    val List = icon(
        "List",
        line = "M9 7h11M9 12h11M9 17h11",
        solid = "M5 5.9a1.1 1.1 0 1 0 0 2.2a1.1 1.1 0 1 0 0-2.2zM5 10.9a1.1 1.1 0 1 0 0 2.2a1.1 1.1 0 1 0 0-2.2zM5 15.9a1.1 1.1 0 1 0 0 2.2a1.1 1.1 0 1 0 0-2.2z",
    )
    val Contrast = icon("Contrast", line = "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0-17 0z", solid = "M12 3.5a8.5 8.5 0 0 1 0 17z")

    /** Four tiles in a grid: "Tidy up" on the home field (#192). */
    val Grid = icon("Grid", line = "M5.8 4h3.4a1.8 1.8 0 0 1 1.8 1.8v3.4a1.8 1.8 0 0 1-1.8 1.8h-3.4a1.8 1.8 0 0 1-1.8-1.8v-3.4a1.8 1.8 0 0 1 1.8-1.8zM14.8 4h3.4a1.8 1.8 0 0 1 1.8 1.8v3.4a1.8 1.8 0 0 1-1.8 1.8h-3.4a1.8 1.8 0 0 1-1.8-1.8v-3.4a1.8 1.8 0 0 1 1.8-1.8zM5.8 13h3.4a1.8 1.8 0 0 1 1.8 1.8v3.4a1.8 1.8 0 0 1-1.8 1.8h-3.4a1.8 1.8 0 0 1-1.8-1.8v-3.4a1.8 1.8 0 0 1 1.8-1.8zM14.8 13h3.4a1.8 1.8 0 0 1 1.8 1.8v3.4a1.8 1.8 0 0 1-1.8 1.8h-3.4a1.8 1.8 0 0 1-1.8-1.8v-3.4a1.8 1.8 0 0 1 1.8-1.8z")
    val Activity = icon("Activity", line = "M5 19.5v-6M12 19.5v-14M19 19.5v-9")

    /** A phone: the digital balance part of the scores (#206). */
    val Phone = icon("Phone", line = "M8.5 3.5h7A1.5 1.5 0 0 1 17 5v14a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 19V5a1.5 1.5 0 0 1 1.5-1.5zM11 17.5h2")
    val Settings = icon(
        "Settings",
        line = "M4 7.5h9M17 7.5h3M4 16.5h3M11 16.5h9M13 7.5a2 2 0 1 0 4 0a2 2 0 1 0-4 0zM7 16.5a2 2 0 1 0 4 0a2 2 0 1 0-4 0z",
    )
}
