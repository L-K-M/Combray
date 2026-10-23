package ch.lkmc.combray.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/*
 * Icons the core Material set lacks. The paths are Material Icons' baseline glyphs (Apache
 * License 2.0, like the icon library), the same style as the bar's other icons, on its 24-unit
 * grid. Save uses bookmarks: Combray saves Karakeep bookmarks, and Karakeep's star means Favourite.
 */

/** Material's "bookmark_border": the page is not saved. */
internal val BookmarkBorderIcon: ImageVector = materialIcon(
    "BookmarkBorder",
    "M17 3L7 3c-1.1 0-1.99 .9-1.99 2L5 21l7-3l7 3L19 5c0-1.1-.9-2-2-2zM17 18l-5-2.18L7 18L7 5h10v13z",
)

/** Material's "bookmark": a save is recorded and on its way. */
internal val BookmarkIcon: ImageVector = materialIcon(
    "Bookmark",
    "M17 3H7c-1.1 0-1.99 .9-1.99 2L5 21l7-3l7 3V5c0-1.1-.9-2-2-2z",
)

/** Material's "bookmark_added": the server acknowledged the save. */
internal val BookmarkAddedIcon: ImageVector = materialIcon(
    "BookmarkAdded",
    "M19 21l-7-3l-7 3V5c0-1.1 .9-2 2-2l7 0c-.63 .84-1 1.87-1 3c0 2.76 2.24 5 5 5c.34 0 .68-.03 1-.1V21z" +
        "M17.83 9L15 6.17l1.41-1.41l1.41 1.41l3.54-3.54l1.41 1.41L17.83 9z",
)

/** Material's "filter_none", two stacked pages, for Recent's tabs: a list glyph read as a menu beside More. */
internal val TabStackIcon: ImageVector = materialIcon(
    "FilterNone",
    "M3 5L1 5v16c0 1.1 .9 2 2 2h16v-2L3 21L3 5zM21 1L7 1c-1.1 0-2 .9-2 2v14c0 1.1 .9 2 2 2h14" +
        "c1.1 0 2-.9 2-2L23 3c0-1.1-.9-2-2-2zM21 17L7 17L7 3h14v14z",
)

/** Material's "schedule" clock: a save waiting for Karakeep to be reachable again. */
internal val ScheduleIcon: ImageVector = materialIcon(
    "Schedule",
    "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2z" +
        "M12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z" +
        "M12.5 7H11v6l5.25 3.15.75-1.23-4.5-2.67z",
)

/** Material's "pause": the close X it replaces in More read as closing the tab. */
internal val PauseIcon: ImageVector = materialIcon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")

/** A 24dp icon of one filled path; [Icon][androidx.compose.material3.Icon] tints it. */
private fun materialIcon(name: String, pathData: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
    .build()
