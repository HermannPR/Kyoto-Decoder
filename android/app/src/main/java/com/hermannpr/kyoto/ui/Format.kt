package com.hermannpr.kyoto.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import java.util.Locale

private val ES = Locale("es", "MX")

fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024L * 1024 -> String.format(ES, "%.1f KB", n / 1024.0)
    n < 1024L * 1024 * 1024 -> String.format(ES, "%.1f MB", n / (1024.0 * 1024))
    else -> String.format(ES, "%.2f GB", n / (1024.0 * 1024 * 1024))
}

fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
    .build()

/** Íconos de Material (no están en material-icons-core). */
object KIcons {
    val Image = icon(
        "image",
        "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
    )
    val Restore = icon(
        "restore",
        "M13,3a9,9 0,0 0,-9 9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42A8.954,8.954 0,0 0,13 21a9,9 0,0 0,0 -18z",
    )
    val Folder = icon(
        "folder",
        "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
    )
    val File = icon(
        "file",
        "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM13,9V3.5L18.5,9H13z",
    )
}
