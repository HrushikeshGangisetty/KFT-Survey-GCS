package com.kft.gcs.ui.design

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import com.kft.gcs.ui.design.res.Res
import com.kft.gcs.ui.design.res.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.vectorResource

/**
 * Every icon in the app. One family only: **Google Material Symbols**, Outlined style, weight 400, 24 px
 * (Apache-2.0, https://github.com/google/material-design-icons; licence and notice in `NOTICE`).
 *
 * Why Material Symbols over Lucide (ISC): they're drawn for M3, so their stroke weight and optical size match the
 * M3 components they sit in; the set has the aviation and mapping glyphs a GCS needs (flight, satellite_alt,
 * my_location, layers); and Google publishes them as Android vector XML, so the files in `composeResources/drawable`
 * are Google's own, unedited except for dropping the Android-only `?attr` tint so they render on desktop too.
 * Lucide ships SVG only, which would mean converting paths by hand.
 *
 * Adding an icon: download `symbols/android/<name>/materialsymbolsoutlined/<name>_24px.xml` from that repository,
 * apply the same tint edit (see the Pass 19a log), save it as `ic_<name>.xml`, and add a line here. Never draw one
 * by hand or take one from another family.
 */
object KftIcons {
    // Navigation rail
    val Fly: ImageVector @Composable get() = vectorResource(Res.drawable.ic_flight)
    val Plan: ImageVector @Composable get() = vectorResource(Res.drawable.ic_route)
    val Params: ImageVector @Composable get() = vectorResource(Res.drawable.ic_tune)
    val Maps: ImageVector @Composable get() = vectorResource(Res.drawable.ic_map)
    val Theme: ImageVector @Composable get() = vectorResource(Res.drawable.ic_contrast)
    val About: ImageVector @Composable get() = vectorResource(Res.drawable.ic_info)

    // Status
    val Ok: ImageVector @Composable get() = vectorResource(Res.drawable.ic_check_circle)
    val Warn: ImageVector @Composable get() = vectorResource(Res.drawable.ic_warning)
    val Critical: ImageVector @Composable get() = vectorResource(Res.drawable.ic_error)
    val Link: ImageVector @Composable get() = vectorResource(Res.drawable.ic_link)
    val LinkOff: ImageVector @Composable get() = vectorResource(Res.drawable.ic_link_off)
    val Gps: ImageVector @Composable get() = vectorResource(Res.drawable.ic_satellite_alt)
    val Login: ImageVector @Composable get() = vectorResource(Res.drawable.ic_key)
    val Sync: ImageVector @Composable get() = vectorResource(Res.drawable.ic_sync)
    val SyncProblem: ImageVector @Composable get() = vectorResource(Res.drawable.ic_sync_problem)

    // Map
    val Center: ImageVector @Composable get() = vectorResource(Res.drawable.ic_my_location)
    val Layers: ImageVector @Composable get() = vectorResource(Res.drawable.ic_layers)
    val ClearTrack: ImageVector @Composable get() = vectorResource(Res.drawable.ic_delete_sweep)

    // Plan toolbar. Files (folder, save, export) and the vehicle (upload, download) use different glyphs on purpose,
    // so "save to a file" and "send to the aircraft" never look alike.
    val AddWaypoint: ImageVector @Composable get() = vectorResource(Res.drawable.ic_add_location_alt)
    val AddSurvey: ImageVector @Composable get() = vectorResource(Res.drawable.ic_grid_on)
    val Undo: ImageVector @Composable get() = vectorResource(Res.drawable.ic_undo)
    val Redo: ImageVector @Composable get() = vectorResource(Res.drawable.ic_redo)
    val Open: ImageVector @Composable get() = vectorResource(Res.drawable.ic_folder_open)
    val Save: ImageVector @Composable get() = vectorResource(Res.drawable.ic_save)
    val Export: ImageVector @Composable get() = vectorResource(Res.drawable.ic_file_export)
    val Upload: ImageVector @Composable get() = vectorResource(Res.drawable.ic_upload)
    val Download: ImageVector @Composable get() = vectorResource(Res.drawable.ic_download)
    val Delete: ImageVector @Composable get() = vectorResource(Res.drawable.ic_delete)
    val Pause: ImageVector @Composable get() = vectorResource(Res.drawable.ic_pause)
    val Resume: ImageVector @Composable get() = vectorResource(Res.drawable.ic_play_arrow)

    // General
    val More: ImageVector @Composable get() = vectorResource(Res.drawable.ic_more_vert)
    val Close: ImageVector @Composable get() = vectorResource(Res.drawable.ic_close)
    val Edit: ImageVector @Composable get() = vectorResource(Res.drawable.ic_edit)
    val Add: ImageVector @Composable get() = vectorResource(Res.drawable.ic_add)
    val Search: ImageVector @Composable get() = vectorResource(Res.drawable.ic_search)
    val Descriptions: ImageVector @Composable get() = vectorResource(Res.drawable.ic_description)
    val ExpandMore: ImageVector @Composable get() = vectorResource(Res.drawable.ic_expand_more)
    val ExpandLess: ImageVector @Composable get() = vectorResource(Res.drawable.ic_expand_less)
    val Check: ImageVector @Composable get() = vectorResource(Res.drawable.ic_check)
}

/**
 * The KFT logo, tinted with the theme's primary colour: the brand navy itself in the light themes, the light navy
 * tint in dark (navy on a navy-dark surface would vanish). The source PNG is single-colour with transparency, which
 * is what makes the tint exact. [full] adds the "KAPIL FUTURE TECH" line (About); without it, just the KFT mark (rail).
 */
@Composable
fun KftLogo(modifier: Modifier = Modifier, full: Boolean = false) {
    Image(
        painterResource(if (full) Res.drawable.kft_logo else Res.drawable.kft_mark),
        contentDescription = "KFT",
        modifier = modifier,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
    )
}

/**
 * The app icon (white KFT mark on navy). A placeholder built from the logo PNG by `tools/brand/make_icons.py` until
 * an SVG of the logo exists. The desktop window uses it; the installers and Android use files made by the same script.
 */
@Composable
fun kftAppIcon(): Painter = painterResource(Res.drawable.kft_app_icon)
