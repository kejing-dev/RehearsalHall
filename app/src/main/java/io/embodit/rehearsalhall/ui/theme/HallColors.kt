package io.embodit.rehearsalhall.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design-package `palette`, `audienceStyle.palette`). This is the only file with colour
 * literals; the four semantic roles go into `PicoTheme` through [HallTheme], the brand tokens feed the toon palette of
 * the hall (Unlit materials) and a few panel accents (zone marks, time bars).
 */
object HallColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Interaction = Color(0xFFF2A64A) // design-style: fixed-figma-color interaction: primary keys, spot amber
    val Passable = Color(0xFF8FD4A8) // design-style: fixed-figma-color passable
    val Alert = Color(0xFFF2A64A) // design-style: fixed-figma-color alert: near / over time
    val Error = Color(0xFFF07167) // design-style: fixed-figma-color error: delete

    // ---- brand tokens
    val SpotAmber = Color(0xFFF2A64A) // design-style: fixed-figma-color spotAmber
    val VelvetSeat = Color(0xFF8E2433) // design-style: fixed-figma-color velvetSeat
    val StageWood = Color(0xFF6B4A32) // design-style: fixed-figma-color stageWood (lectern)
    val StageFloor = Color(0xFFB07A4F) // design-style: fixed-figma-color hall.style stage warm wood
    val StepA = Color(0xFF3A2A3A) // design-style: fixed-figma-color hall.style steps
    val StepB = Color(0xFF43304A) // design-style: fixed-figma-color hall.style steps
    val HouseDark = Color(0xFF16121A) // design-style: fixed-figma-color houseDark
    val ExitGreen = Color(0xFF2FBF71) // design-style: fixed-figma-color exitGreen
    val Beam = Color(0xFFFFE3B0) // design-style: fixed-figma-color beam (drawn at 18 %)
    val Ink = Color(0xFF1E1418) // design-style: fixed-figma-color ink on amber fills
    val Cream = Color(0xFFFFE7C2) // design-style: fixed-figma-color appIcon glyph

    /** Seat tint ramp, cold slate to warm amber (design seatScale). */
    val SeatScale = listOf(Color(0xFF5B6B82), Color(0xFF8A8476), Color(0xFFC29A62), Color(0xFFE3A24E), Color(0xFFFFC46B)) // design-style: fixed-figma-color seatScale

    val Skin = listOf(Color(0xFFF6D3B8), Color(0xFFEDBB99), Color(0xFFD49A72), Color(0xFFA86B4A), Color(0xFF7A4A33)) // design-style: fixed-figma-color audienceSkin
    val Hair = listOf(Color(0xFF2B2220), Color(0xFF4A3226), Color(0xFF8A5A3B), Color(0xFFC98B4E), Color(0xFFE8C27A), Color(0xFF8C8C96)) // design-style: fixed-figma-color audienceHair
    val Clothes = listOf(Color(0xFF4F7CAC), Color(0xFF6BA292), Color(0xFFE9B44C), Color(0xFFF3E9DC), Color(0xFF7D6B91), Color(0xFF3E5C76), Color(0xFFD9A27E), Color(0xFF9DB4C0)) // design-style: fixed-figma-color audienceClothes
}
