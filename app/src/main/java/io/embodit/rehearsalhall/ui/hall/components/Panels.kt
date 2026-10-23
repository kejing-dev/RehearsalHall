package io.embodit.rehearsalhall.ui.hall.components

import android.content.res.Resources
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.LinearProgressDefaults
import com.pico.spatial.ui.design.LinearProgressIndicator
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.ScrollIndicator
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.rehearsalhall.R
import io.embodit.rehearsalhall.domain.Clock
import io.embodit.rehearsalhall.domain.Deck
import io.embodit.rehearsalhall.domain.Report
import io.embodit.rehearsalhall.domain.Samples
import io.embodit.rehearsalhall.domain.Session
import io.embodit.rehearsalhall.domain.Voice
import io.embodit.rehearsalhall.domain.ZoneLevel
import io.embodit.rehearsalhall.ui.hall.Base
import io.embodit.rehearsalhall.ui.hall.ConfirmKind
import io.embodit.rehearsalhall.ui.hall.HallController
import io.embodit.rehearsalhall.ui.hall.HallEvent
import io.embodit.rehearsalhall.ui.hall.HallUiState
import io.embodit.rehearsalhall.ui.hall.ImportError
import io.embodit.rehearsalhall.ui.hall.LanguagePref
import io.embodit.rehearsalhall.ui.hall.Lights
import io.embodit.rehearsalhall.ui.hall.MicPermission
import io.embodit.rehearsalhall.ui.hall.ReportTab
import io.embodit.rehearsalhall.ui.hall.Tip
import io.embodit.rehearsalhall.ui.hall.debug.probe
import io.embodit.rehearsalhall.ui.theme.HallColors
import io.embodit.rehearsalhall.ui.theme.HallType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val GlassShape = RoundedCornerShape(28.dp)

/** Glass panel root (design: Material.Regular, the root draws no extra background). */
@Composable
private fun Glass(w: Float, h: Float, dim: Boolean = false, padding: Int = 20, content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier.size(w.dp, h.dp)
                .clip(GlassShape)
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
    ) {
        // design glassFallback (N10): with the house lights at 15 % an inner layer keeps the text readable
        if (dim) Box(Modifier.fillMaxSize().background(PicoTheme.colorScheme.fillTertiary))
        Box(Modifier.fillMaxSize().padding(padding.dp)) { content() }
    }
}

// =============================================================================================
// lectern panel (design panels[0]): one page at a time, title bar on top, primary key bottom right
// =============================================================================================

@Composable
internal fun LecternPanel(state: HallUiState?, onEvent: (HallEvent) -> Unit) {
    val dim = state != null && state.lights == Lights.TALK
    Glass(PanelSizes.LECTERN_W, PanelSizes.LECTERN_H, dim = dim) {
        if (state == null) return@Glass
        Column(modifier = Modifier.fillMaxSize()) {
            when (state.base) {
                Base.S00 -> Loading()
                Base.S01 -> Setup(state, onEvent)
                Base.S02 -> Decks(state, onEvent)
                Base.S03 -> Importing(state, onEvent)
                Base.S04 -> ImportFailed(state, onEvent)
                Base.S05 -> Countdown(state, onEvent)
                Base.S06 -> Speaking(state, onEvent)
                Base.S07 -> Paused(state, onEvent)
                Base.S08 -> EndConfirm(onEvent)
                Base.S09 -> ReportPage(state, onEvent)
                Base.S10 -> ReplayPage(state, onEvent)
                Base.S11 -> Settings(state, onEvent)
                Base.S12 -> History(state, onEvent)
                Base.S13 -> MicAsk(onEvent)
                Base.S14 -> ConfirmPage(state, onEvent)
                Base.S15 -> TooShort(onEvent)
            }
        }
    }
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun TitleBar(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (onBack != null) {
            SmallIcon(R.drawable.ic_chevron_left, rs(R.string.back), "back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
        }
        Text(text = title, style = HallType.title, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun TipLine(tip: Tip?, onEvent: (HallEvent) -> Unit) {
    if (tip == null) return
    val text =
        when (tip) {
            Tip.FIRST_RUN -> rs(R.string.tip_first_run)
            Tip.FIRST_TALK -> rs(R.string.tip_first_talk)
            Tip.INTERRUPTED -> rs(R.string.tip_interrupted)
            Tip.MIC_DENIED -> rs(R.string.tip_mic_denied)
            Tip.IMPORT_CAPPED -> rs(R.string.tip_import_capped)
            Tip.PICKER_MISSING -> rs(R.string.tip_picker_missing)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(PicoTheme.colorScheme.fillSecondary).padding(start = 14.dp, end = 4.dp).height(40.dp),
    ) {
        Text(text = text, style = HallType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        SmallIcon(R.drawable.ic_close, rs(R.string.close), "tip_close", size = 32) { onEvent(HallEvent.DismissTip) }
    }
}

@Composable
private fun SmallIcon(icon: Int, desc: String, name: String, size: Int = 44, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val ink = if (active) HallColors.Ink else PicoTheme.colorScheme.labelPrimary
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.probe(name),
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (active) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
        size = IconButtonDefaults.iconButtonSize(width = size.dp, height = size.dp),
    ) { Icon(painter = painterResource(icon), contentDescription = desc, modifier = Modifier.size((size * 0.48f).dp), tint = ink) }
}

@Composable
private fun Primary(text: String, name: String, large: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name),
        size = if (large) ButtonDefaults.Regular.copy(height = 56.dp) else ButtonDefaults.Regular.copy(height = 48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = HallColors.Ink),
    ) { Text(text = text, maxLines = 1, color = HallColors.Ink) }
}

@Composable
private fun Secondary(text: String, name: String, large: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val ink = if (danger) PicoTheme.colorScheme.error else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name),
        size = if (large) ButtonDefaults.Regular.copy(height = 56.dp) else ButtonDefaults.Regular.copy(height = 48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) { Text(text = text, maxLines = 1, color = ink) }
}

@Composable
private fun TextKey(text: String, name: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name),
        size = ButtonDefaults.Regular.copy(height = 48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) { Text(text = text, maxLines = 1, style = HallType.body) }
}

/** The bottom row: secondary keys, then the primary one in the bottom-right corner. */
@Composable
private fun BottomRow(content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) { content() }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, lines: Int = 3) {
    Text(text = text, style = HallType.body, color = if (secondary) PicoTheme.colorScheme.labelSecondary else PicoTheme.colorScheme.labelPrimary, modifier = modifier, maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Thumb(deckId: String, page: Int, w: Int, h: Int) {
    val bmp = remember(deckId, page, HallController.state.value.language) { HallController.pageBitmap(deckId, page) }
    Box(modifier = Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(10.dp)).background(PicoTheme.colorScheme.fillTertiary)) {
        if (bmp != null) Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

private fun Resources.deckLine(d: Deck): String =
    when {
        d.noSlides -> getString(R.string.deck_none_sub)
        d.hasNotes -> getString(R.string.deck_pages_notes, d.pageCount)
        else -> getString(R.string.deck_pages, d.pageCount)
    }

// ---------------------------------------------------------------- S00 / S01

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Loading() {
    TitleBar(rs(R.string.app_name))
    Spacer(Modifier.weight(1f))
    Body(rs(R.string.loading), secondary = true, modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.weight(1f))
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Setup(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    val r = LocalHallResources.current
    TitleBar(rs(R.string.app_name)) {
        SmallIcon(R.drawable.ic_history, rs(R.string.history), "setup_history", enabled = state.history.isNotEmpty()) { onEvent(HallEvent.OpenHistory) }
        Spacer(Modifier.width(10.dp))
        SmallIcon(R.drawable.ic_gear, rs(R.string.settings), "setup_settings") { onEvent(HallEvent.OpenSettings) }
    }
    TipLine(state.tip?.takeIf { it != Tip.FIRST_TALK }, onEvent)
    val deck = state.deck
    ListItem(
        leadingContent = { if (deck != null) Thumb(deck.id, 0, 96, 54) },
        headlineContent = { Text(text = deck?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, color = PicoTheme.colorScheme.labelPrimary) },
        supportingContent = { Text(text = deck?.let { r.deckLine(it) } ?: "", maxLines = 1, style = HallType.label, color = PicoTheme.colorScheme.labelSecondary) },
        trailingContent = { TextKey(rs(R.string.change_deck), "setup_change") { onEvent(HallEvent.ChangeDeck) } },
    )
    ListItem(
        headlineContent = { Text(text = rs(R.string.target), maxLines = 1, color = PicoTheme.colorScheme.labelPrimary) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallIcon(R.drawable.ic_minus, "-", "target_minus", enabled = state.targetMin > 1) { onEvent(HallEvent.TargetStep(-1)) }
                Text(text = rs(R.string.minutes, state.targetMin), style = HallType.titleMedium, color = PicoTheme.colorScheme.labelPrimary, textAlign = TextAlign.Center, modifier = Modifier.width(96.dp), maxLines = 1)
                SmallIcon(R.drawable.ic_plus, "+", "target_plus", enabled = state.targetMin < 60) { onEvent(HallEvent.TargetStep(1)) }
            }
        },
    )
    ListItem(
        headlineContent = { Text(text = rs(R.string.mic_row), maxLines = 1, color = PicoTheme.colorScheme.labelPrimary) },
        supportingContent = if (!state.micOn) ({ Text(text = rs(R.string.mic_row_off), maxLines = 1, style = HallType.label, color = PicoTheme.colorScheme.labelSecondary) }) else null,
        trailingContent = { Switch(checked = state.micOn, onCheckedChange = { onEvent(HallEvent.MicToggle(it)) }, modifier = Modifier.probe("setup_mic")) },
    )
    Spacer(Modifier.weight(1f))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val last = state.lastSummary
        Body(if (last != null) lastLine(r, last) else "", secondary = true, modifier = Modifier.weight(1f), lines = 1)
        Primary(rs(R.string.start), "setup_start") { onEvent(HallEvent.Start) }
    }
}

private fun lastLine(r: Resources, s: Session): String {
    val rep = s.report
    val over = s.durationSec - s.targetSec
    val time = if (over > 0) r.getString(R.string.last_over, Clock.mmss(over)) else r.getString(R.string.last_time, Clock.mmss(s.durationSec))
    return r.getString(R.string.last_line, rep.covered, time)
}

// ---------------------------------------------------------------- S02 decks

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Decks(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    val r = LocalHallResources.current
    TitleBar(rs(R.string.decks_title), onBack = { onEvent(HallEvent.Back) })
    TipLine(state.tip?.takeIf { it == Tip.IMPORT_CAPPED || it == Tip.PICKER_MISSING }, onEvent)
    var open by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(state.decks, key = { it.id }) { d ->
                val imported = d.source == Deck.SOURCE_PDF || d.source == Deck.SOURCE_IMAGES
                Column {
                    ListItem(
                        leadingContent = { if (!d.noSlides) Thumb(d.id, 0, 80, 45) else Box(Modifier.size(80.dp, 45.dp)) },
                        headlineContent = { Text(text = if (d.source == Deck.SOURCE_SAMPLE) rs(R.string.sample_prefix, d.title) else d.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = PicoTheme.colorScheme.labelPrimary) },
                        supportingContent = {
                            val date = if (imported) " · " + SimpleDateFormat(if (r.zh()) "M月d日" else "MMM d", if (r.zh()) Locale.CHINA else Locale.US).format(Date(d.createdAt)) else ""
                            Text(text = r.deckLine(d) + date, maxLines = 1, style = HallType.label, color = PicoTheme.colorScheme.labelSecondary)
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (d.id == state.deckId) Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.interaction)
                                if (imported) {
                                    Spacer(Modifier.width(8.dp))
                                    SmallIcon(R.drawable.ic_more, rs(R.string.more), "deck_more_${d.id}", size = 40, active = open == d.id) { open = if (open == d.id) null else d.id }
                                }
                            }
                        },
                        modifier = Modifier.clickable { onEvent(HallEvent.PickDeck(d.id)) }.probe("deck_${d.id}"),
                    )
                    // the row's "⋯" actions open inline (a Stage has no popup menus)
                    if (open == d.id) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Secondary(rs(R.string.import_notes), "deck_notes", large = false) { onEvent(HallEvent.ImportNotes(d.id)) }
                            Secondary(rs(R.string.delete), "deck_delete", large = false, danger = true) { onEvent(HallEvent.DeleteDeck(d.id)) }
                        }
                    }
                }
            }
        }
        ScrollIndicator(state = list)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(text = rs(R.string.import_hint), style = HallType.label, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.weight(1f), maxLines = 3)
        Spacer(Modifier.width(12.dp))
        Primary(rs(R.string.import_files), "decks_import", large = false) { onEvent(HallEvent.ImportFiles) }
    }
}

// ---------------------------------------------------------------- S03 / S04

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Importing(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    TitleBar(rs(R.string.importing_title))
    Spacer(Modifier.weight(1f))
    Body(if (state.importTotal > 0) rs(R.string.importing_page, state.importDone, state.importTotal) else rs(R.string.importing_start))
    Spacer(Modifier.height(14.dp))
    LinearProgressIndicator(
        progress = { if (state.importTotal > 0) state.importDone.toFloat() / state.importTotal else 0f },
        modifier = Modifier.fillMaxWidth(),
        colors = LinearProgressDefaults.linearProgressColors(indicatorColor = PicoTheme.colorScheme.interaction, backgroundColor = PicoTheme.colorScheme.fillTertiary),
        height = LinearProgressDefaults.linearProgressHeight(10.dp),
    )
    Spacer(Modifier.weight(1f))
    BottomRow { Secondary(rs(R.string.cancel), "import_cancel") { onEvent(HallEvent.CancelImport) } }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ImportFailed(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    TitleBar(rs(R.string.import_failed_title), onBack = { onEvent(HallEvent.Back) })
    Spacer(Modifier.weight(1f))
    Text(
        text = rs(when (state.importError) { ImportError.PASSWORD -> R.string.import_err_password; ImportError.NOT_SUPPORTED -> R.string.import_err_type; else -> R.string.import_err_failed }),
        style = HallType.title,
        color = PicoTheme.colorScheme.labelPrimary,
    )
    Spacer(Modifier.weight(1f))
    BottomRow {
        Secondary(rs(R.string.back), "import_back") { onEvent(HallEvent.Back) }
        Primary(rs(R.string.import_retry), "import_retry") { onEvent(HallEvent.RetryImport) }
    }
}

// ---------------------------------------------------------------- S05 - S08, S15

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Countdown(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    TitleBar(rs(R.string.countdown_title))
    Spacer(Modifier.weight(1f))
    Text(text = state.countdown.toString(), style = HallType.display.copy(fontSize = androidx.compose.ui.unit.TextUnit(96f, androidx.compose.ui.unit.TextUnitType.Sp)), color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.align(Alignment.CenterHorizontally))
    Body(rs(R.string.countdown_hint), secondary = true, modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.weight(1f))
    BottomRow { Secondary(rs(R.string.cancel), "countdown_cancel") { onEvent(HallEvent.CancelCountdown) } }
}

@Composable
private fun TotalClock(state: HallUiState, big: Boolean) {
    val over = state.overTime
    val near = state.nearTime
    val color = if (over || near) PicoTheme.colorScheme.alert else PicoTheme.colorScheme.labelPrimary
    val main =
        when {
            over -> "▲ +" + Clock.mmss(state.elapsedSec - state.targetSec)
            near -> "◐ " + Clock.mmss2(state.elapsedSec)
            else -> Clock.mmss2(state.elapsedSec)
        }
    val style = if (big) HallType.display.copy(fontSize = androidx.compose.ui.unit.TextUnit(72f, androidx.compose.ui.unit.TextUnitType.Sp)) else HallType.display
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text = main, style = if (over) style.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else style, color = color, maxLines = 1)
        Text(text = " / " + Clock.mmss2(state.targetSec.toFloat()), style = HallType.titleMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Speaking(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    val deck = state.deck
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        if (state.micOn) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (state.micDot) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.fillSecondary).probe("mic_dot"))
            Spacer(Modifier.width(10.dp))
        }
        if (!state.noSlides) TotalClock(state, big = false)
        Spacer(Modifier.weight(1f))
        if (!state.noSlides) Text(text = rs(R.string.this_page, Clock.mmss(state.pageSec)), style = HallType.titleMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    }
    TipLine(state.tip?.takeIf { it == Tip.FIRST_TALK }, onEvent)
    if (state.noSlides || deck == null) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { TotalClock(state, big = true) }
        BottomRow {
            SmallIcon(R.drawable.ic_pause, rs(R.string.pause), "talk_pause", size = 56) { onEvent(HallEvent.Pause) }
            Secondary(rs(R.string.end), "talk_end") { onEvent(HallEvent.End) }
        }
        return
    }
    Row(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
        Column {
            Thumb(deck.id, state.page, 300, 169)
            Spacer(Modifier.height(6.dp))
            Text(text = "${state.page + 1} / ${deck.pageCount}", style = HallType.label, color = PicoTheme.colorScheme.labelSecondary)
        }
        Spacer(Modifier.width(18.dp))
        val note = deck.notes.getOrNull(state.page).orEmpty()
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (note.isNotBlank()) {
                Text(text = note, style = HallType.titleMedium, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.verticalScroll(rememberScrollState()))
            } else if (state.page + 1 < deck.pageCount) {
                Column {
                    Text(text = rs(R.string.next_page_preview), style = HallType.label, color = PicoTheme.colorScheme.labelSecondary)
                    Spacer(Modifier.height(6.dp))
                    Thumb(deck.id, state.page + 1, 220, 124)
                }
            }
        }
    }
    BottomRow {
        Secondary(rs(R.string.prev_page), "talk_prev") { onEvent(HallEvent.PrevPage) }
        Primary(rs(R.string.next_page), "talk_next") { onEvent(HallEvent.NextPage) }
        SmallIcon(R.drawable.ic_pause, rs(R.string.pause), "talk_pause", size = 56) { onEvent(HallEvent.Pause) }
        Secondary(rs(R.string.end), "talk_end") { onEvent(HallEvent.End) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Paused(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    TitleBar(rs(R.string.paused_title, Clock.mmss2(state.elapsedSec)))
    Spacer(Modifier.weight(1f))
    Body(rs(R.string.paused_hint), secondary = true)
    Spacer(Modifier.height(10.dp))
    TextKey(rs(R.string.replace), "paused_replace") { onEvent(HallEvent.Replace) }
    Spacer(Modifier.weight(1f))
    BottomRow {
        Secondary(rs(R.string.end), "paused_end") { onEvent(HallEvent.End) }
        Primary(rs(R.string.resume), "paused_resume") { onEvent(HallEvent.Resume) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.EndConfirm(onEvent: (HallEvent) -> Unit) {
    TitleBar(rs(R.string.end_title))
    Spacer(Modifier.weight(1f))
    Body(rs(R.string.end_hint), secondary = true)
    Spacer(Modifier.weight(1f))
    BottomRow {
        Secondary(rs(R.string.keep_talking), "end_keep") { onEvent(HallEvent.KeepTalking) }
        Primary(rs(R.string.end_confirm), "end_confirm") { onEvent(HallEvent.EndConfirmed) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TooShort(onEvent: (HallEvent) -> Unit) {
    TitleBar(rs(R.string.too_short_title))
    Spacer(Modifier.weight(1f))
    Text(text = rs(R.string.too_short), style = HallType.title, color = PicoTheme.colorScheme.labelPrimary)
    Spacer(Modifier.weight(1f))
    BottomRow {
        Secondary(rs(R.string.back), "short_back") { onEvent(HallEvent.Back) }
        Primary(rs(R.string.again), "short_again") { onEvent(HallEvent.Again) }
    }
}

// ---------------------------------------------------------------- S09 report

private fun Resources.verdict(rep: Report): String {
    val v = rep.verdict
    val head =
        when (v) {
            is Report.Verdict.NoAudience -> getString(R.string.verdict_no_audience, v.lecternPct, v.backPct)
            Report.Verdict.All -> getString(R.string.verdict_all)
            is Report.Verdict.Weakest -> getString(R.string.verdict_weakest, zoneName(v.zone))
            is Report.Verdict.Cold -> getString(R.string.verdict_cold, v.zones.joinToString(getString(R.string.list_sep)) { zoneName(it) })
        }
    val extras = ArrayList<String>()
    if (rep.lecternHeavy) extras += getString(R.string.verdict_lectern_heavy)
    if (rep.backHeavy) extras += getString(R.string.verdict_back_heavy)
    return if (extras.isEmpty()) head else head + getString(R.string.sentence_sep) + extras.joinToString(getString(R.string.sentence_sep))
}

private fun mark(l: ZoneLevel): String = when (l) { ZoneLevel.COLD -> "◯"; ZoneLevel.MID -> "◐"; ZoneLevel.HOT -> "●" }

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReportPage(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    val view = state.report ?: return
    val s = view.session
    val rep = s.report
    val r = LocalHallResources.current
    TitleBar(rs(R.string.report_title), onBack = if (view.readOnly) ({ onEvent(HallEvent.Back) }) else null) {
        SegmentControl(modifier = Modifier.width(300.dp).height(44.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
            listOf(ReportTab.GAZE to R.string.tab_gaze, ReportTab.TIME to R.string.tab_time, ReportTab.VOICE to R.string.tab_voice).forEach { (tab, label) ->
                SegmentItem(
                    selected = state.tab == tab,
                    onClick = { onEvent(HallEvent.Tab(tab)) },
                    modifier = Modifier.width(100.dp).fillMaxHeight().probe("tab_${tab.name.lowercase()}"),
                    textStyle = PicoTheme.typography.labelLarge,
                    colors = segColors(),
                    title = { Text(text = rs(label), maxLines = 1, color = PicoTheme.colorScheme.labelPrimary) },
                )
            }
        }
    }
    val prev = view.previous
    if (prev != null && !view.readOnly) {
        val overNow = (s.durationSec - s.targetSec).coerceAtLeast(0f)
        val overPrev = (prev.durationSec - prev.targetSec).coerceAtLeast(0f)
        Body(rs(R.string.vs_last, prev.report.covered, rep.covered, Clock.mmss(overPrev), Clock.mmss(overNow)), secondary = true, lines = 1)
    }
    Spacer(Modifier.height(6.dp))
    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        when (state.tab) {
            ReportTab.GAZE -> GazeTab(rep, s, r)
            ReportTab.TIME -> TimeTab(state, s)
            ReportTab.VOICE -> VoiceTab(s, onEvent)
        }
    }
    BottomRow {
        if (view.readOnly) {
            Secondary(rs(R.string.replay), "report_replay") { onEvent(HallEvent.Replay) }
            Primary(rs(R.string.back), "report_back") { onEvent(HallEvent.Back) }
        } else {
            TextKey(rs(R.string.done), "report_done") { onEvent(HallEvent.Done) }
            Secondary(rs(R.string.replay), "report_replay") { onEvent(HallEvent.Replay) }
            Primary(rs(R.string.again_full), "report_again") { onEvent(HallEvent.Again) }
        }
    }
}

@Composable
private fun GazeTab(rep: Report, s: Session, r: Resources) {
    Column {
        Text(text = r.verdict(rep), style = HallType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 3 x 3 grid, back row on top as seen from the stage
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (tier in 2 downTo 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (col in 0 until 3) {
                            val z = tier * 3 + col
                            val l = rep.levels[z]
                            val fill = when (l) { ZoneLevel.COLD -> HallColors.SeatScale[0]; ZoneLevel.MID -> HallColors.SeatScale[2]; ZoneLevel.HOT -> HallColors.SeatScale[4] }
                            Box(modifier = Modifier.size(96.dp, 40.dp).clip(RoundedCornerShape(8.dp)).background(fill.copy(alpha = 0.85f)).probe("zone_$z"), contentAlignment = Alignment.Center) {
                                Text(text = mark(l) + " " + r.getString(R.string.seconds, s.zoneDwell.getOrElse(z) { 0f }.roundToInt()), style = HallType.label, color = HallColors.Ink, maxLines = 1)
                            }
                        }
                    }
                }
                Text(text = rs(R.string.grid_stage), style = HallType.label, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(296.dp), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareLine(rs(R.string.look_audience), rep.audiencePct)
                ShareLine(rs(R.string.look_lectern), rep.lecternPct)
                ShareLine(rs(R.string.look_back), rep.backPct)
                Text(text = rs(R.string.zones_covered, rep.covered), style = HallType.label, color = PicoTheme.colorScheme.labelSecondary)
            }
        }
    }
}

@Composable
private fun ShareLine(label: String, pct: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = HallType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(110.dp), maxLines = 1)
        Text(text = "$pct%", style = HallType.titleMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    }
}

@Composable
private fun TimeTab(state: HallUiState, s: Session) {
    val over = s.durationSec - s.targetSec
    Column {
        val head =
            if (over > 0) rs(R.string.time_over, Clock.mmss(s.durationSec), Clock.mmss(s.targetSec.toFloat()), Clock.mmss(over))
            else rs(R.string.time_under, Clock.mmss(s.durationSec), Clock.mmss(s.targetSec.toFloat()))
        Text(text = head, style = HallType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        if (s.pageTimes.isEmpty()) return@Column
        val per = s.targetSec.toFloat() / s.pageTimes.size
        val maxV = maxOf(per, s.pageTimes.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val list = rememberLazyListState()
        Box {
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(s.pageTimes.size) { i ->
                    val t = s.pageTimes[i]
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(22.dp)) {
                        Text(text = rs(R.string.page_n, i + 1), style = HallType.label, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(64.dp), maxLines = 1)
                        val full = 460f
                        val base = (minOf(t, per) / maxV * full)
                        val extra = ((t - per).coerceAtLeast(0f) / maxV * full)
                        Box(Modifier.size(base.dp.coerceAtLeast(2.dp), 14.dp).clip(RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp)).background(PicoTheme.colorScheme.fillSecondary))
                        if (extra > 0.5f) Box(Modifier.size(extra.dp, 14.dp).clip(RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)).background(HallColors.SpotAmber))
                        Spacer(Modifier.width(8.dp))
                        Text(text = Clock.mmss(t), style = HallType.label, color = if (t > per) PicoTheme.colorScheme.alert else PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                    }
                }
            }
            ScrollIndicator(state = list)
        }
    }
}

@Composable
private fun VoiceTab(s: Session, onEvent: (HallEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val v = s.voice) {
            null -> {
                Text(text = rs(R.string.voice_off), style = HallType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
                if (!HallController.state.value.micOn) Secondary(rs(R.string.voice_turn_on), "voice_turn_on", large = false) { onEvent(HallEvent.MicToggle(true)) }
            }
            Voice.NoSignal -> Text(text = rs(R.string.voice_no_signal), style = HallType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
            is Voice.Metrics -> {
                ShareLine(rs(R.string.voice_speaking), (v.speakRatio * 100).roundToInt())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = rs(R.string.voice_pause), style = HallType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(110.dp), maxLines = 1)
                    Text(text = if (v.longestPauseSec > 0f) rs(R.string.voice_pause_value, v.longestPauseSec.roundToInt(), v.longestPausePage + 1) else rs(R.string.voice_pause_none), style = HallType.titleMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = rs(R.string.voice_level), style = HallType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(110.dp), maxLines = 1)
                    Text(text = rs(if (v.quiet) R.string.voice_quiet else R.string.voice_normal), style = HallType.titleMedium, color = if (v.quiet) PicoTheme.colorScheme.alert else PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                }
                Body(rs(R.string.voice_privacy), secondary = true, lines = 1)
            }
        }
    }
}

@Composable
private fun segColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = PicoTheme.colorScheme.labelPrimary,
        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
    )

// ---------------------------------------------------------------- S10 replay

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReplayPage(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    val view = state.report ?: return
    val s = view.session
    TitleBar(rs(R.string.replay_title))
    val samples = remember(s.id) { Samples.decode(s.samples) }
    val page = samples.getOrNull((state.replaySec * 10).toInt().coerceIn(0, (samples.size - 1).coerceAtLeast(0)))?.page ?: 0
    Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
        if (!state.decks.firstOrNull { it.id == s.deckId }.let { it == null || it.noSlides }) {
            Column {
                Thumb(s.deckId, page, 300, 169)
                Spacer(Modifier.height(6.dp))
                Text(text = rs(R.string.page_n, page + 1), style = HallType.label, color = PicoTheme.colorScheme.labelSecondary)
            }
            Spacer(Modifier.width(20.dp))
        }
        Body(rs(R.string.replay_hint), secondary = true, modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val done = state.replaySec >= s.durationSec
        SmallIcon(if (state.replayPlaying) R.drawable.ic_pause else if (done) R.drawable.ic_history else R.drawable.ic_play, rs(if (state.replayPlaying) R.string.pause else if (done) R.string.replay_again else R.string.play), "replay_toggle", size = 48) { onEvent(HallEvent.ReplayToggle) }
        Spacer(Modifier.width(14.dp))
        LinearProgressIndicator(
            progress = { if (s.durationSec > 0f) (state.replaySec / s.durationSec).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.weight(1f),
            colors = LinearProgressDefaults.linearProgressColors(indicatorColor = PicoTheme.colorScheme.interaction, backgroundColor = PicoTheme.colorScheme.fillTertiary),
            height = LinearProgressDefaults.linearProgressHeight(8.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(text = Clock.mmss(state.replaySec) + " / " + Clock.mmss(s.durationSec), style = HallType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    }
    BottomRow { Primary(rs(R.string.replay_back), "replay_back") { onEvent(HallEvent.ReplayBack) } }
}

// ---------------------------------------------------------------- S11 - S14

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Settings(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    TitleBar(rs(R.string.settings), onBack = { onEvent(HallEvent.Back) })
    Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
        ListItem(
            headlineContent = { Text(text = rs(R.string.language), maxLines = 1, color = PicoTheme.colorScheme.labelPrimary) },
            trailingContent = {
                SegmentControl(modifier = Modifier.width(330.dp).height(44.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
                    listOf(LanguagePref.SYSTEM to R.string.lang_system, LanguagePref.ZH to R.string.lang_zh, LanguagePref.EN to R.string.lang_en).forEach { (p, label) ->
                        SegmentItem(
                            selected = state.language == p,
                            onClick = { onEvent(HallEvent.Language(p)) },
                            modifier = Modifier.width(110.dp).fillMaxHeight().probe("lang_${p.name.lowercase()}"),
                            textStyle = PicoTheme.typography.labelLarge,
                            colors = segColors(),
                            title = { Text(text = rs(label), maxLines = 1, color = PicoTheme.colorScheme.labelPrimary) },
                        )
                    }
                }
            },
        )
        ListItem(
            headlineContent = { Text(text = rs(R.string.drift_row), maxLines = 1, color = PicoTheme.colorScheme.labelPrimary) },
            supportingContent = { Text(text = rs(R.string.drift_sub), maxLines = 1, style = HallType.label, color = PicoTheme.colorScheme.labelSecondary) },
            trailingContent = { Switch(checked = state.driftOn, onCheckedChange = { onEvent(HallEvent.Drift(it)) }, modifier = Modifier.probe("settings_drift")) },
        )
        ListItem(
            headlineContent = { Text(text = rs(R.string.replace), maxLines = 1, color = PicoTheme.colorScheme.labelPrimary) },
            supportingContent = { Text(text = rs(R.string.replace_sub), maxLines = 1, style = HallType.label, color = PicoTheme.colorScheme.labelSecondary) },
            trailingContent = { Secondary(rs(R.string.replace_key), "settings_replace", large = false) { onEvent(HallEvent.Replace) } },
        )
        ListItem(
            headlineContent = { Text(text = rs(R.string.mic_status), maxLines = 1, color = PicoTheme.colorScheme.labelPrimary) },
            supportingContent = { Text(text = rs(if (state.micPermission == MicPermission.GRANTED) R.string.mic_granted else R.string.mic_not_granted), maxLines = 1, style = HallType.label, color = PicoTheme.colorScheme.labelSecondary) },
            trailingContent = { if (state.micPermission != MicPermission.GRANTED) Secondary(rs(R.string.mic_go_settings), "settings_mic", large = false) { onEvent(HallEvent.MicSettings) } },
        )
        ListItem(
            headlineContent = { Text(text = rs(R.string.clear_all), maxLines = 1, color = PicoTheme.colorScheme.error) },
            trailingContent = { Secondary(rs(R.string.clear_key), "settings_clear", large = false, danger = true) { onEvent(HallEvent.ClearAll) } },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.History(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    val r = LocalHallResources.current
    TitleBar(rs(R.string.history_title, state.deck?.title ?: ""), onBack = { onEvent(HallEvent.Back) })
    val list = rememberLazyListState()
    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(state = list) {
            items(state.history, key = { it.id }) { s ->
                val fmt = SimpleDateFormat(if (r.zh()) "M月d日 HH:mm" else "MMM d, HH:mm", if (r.zh()) Locale.CHINA else Locale.US)
                ListItem(
                    headlineContent = { Text(text = fmt.format(Date(s.startedAt)), maxLines = 1, color = PicoTheme.colorScheme.labelPrimary) },
                    supportingContent = { Text(text = rs(R.string.history_line, Clock.mmss(s.durationSec), Clock.mmss(s.targetSec.toFloat()), s.report.covered), maxLines = 1, style = HallType.label, color = PicoTheme.colorScheme.labelSecondary) },
                    trailingContent = { Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelSecondary) },
                    modifier = Modifier.clickable { onEvent(HallEvent.OpenSession(s.id)) }.probe("history_${s.id}"),
                )
            }
        }
        ScrollIndicator(state = list)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.MicAsk(onEvent: (HallEvent) -> Unit) {
    TitleBar(rs(R.string.mic_ask_title), onBack = { onEvent(HallEvent.Back) })
    Spacer(Modifier.weight(1f))
    Text(text = rs(R.string.mic_ask_body), style = HallType.titleMedium, color = PicoTheme.colorScheme.labelPrimary)
    Spacer(Modifier.weight(1f))
    BottomRow {
        Secondary(rs(R.string.mic_decline), "mic_decline") { onEvent(HallEvent.MicDecline) }
        Primary(rs(R.string.mic_allow), "mic_allow") { onEvent(HallEvent.MicAllow) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ConfirmPage(state: HallUiState, onEvent: (HallEvent) -> Unit) {
    val c = state.confirm
    TitleBar(rs(R.string.confirm_title))
    Spacer(Modifier.weight(1f))
    Text(
        text = if (c?.kind == ConfirmKind.DELETE_DECK) rs(R.string.confirm_delete_deck, c.sessions) else rs(R.string.confirm_clear_all),
        style = HallType.title,
        color = PicoTheme.colorScheme.labelPrimary,
    )
    Spacer(Modifier.weight(1f))
    BottomRow {
        Secondary(rs(R.string.cancel), "confirm_no") { onEvent(HallEvent.ConfirmNo) }
        Button(
            onClick = { onEvent(HallEvent.ConfirmYes) },
            modifier = Modifier.probe("confirm_yes"),
            size = ButtonDefaults.Regular.copy(height = 56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.error, contentColor = HallColors.Ink),
        ) { Text(text = rs(R.string.delete), maxLines = 1, color = HallColors.Ink) }
    }
}

// =============================================================================================
// zone signs (design panels[1]): "左后 · 0 秒" + ◯ / ◐ / ●, read-only
// =============================================================================================

@Composable
internal fun ZoneSign(state: HallUiState?, zone: Int) {
    Glass(PanelSizes.SIGN_W, PanelSizes.SIGN_H, padding = 10) {
        val s = state?.report?.session ?: return@Glass
        val r = LocalHallResources.current
        val rep = s.report
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(text = mark(rep.levels[zone]), style = HallType.sign, color = when (rep.levels[zone]) { ZoneLevel.COLD -> HallColors.SeatScale[0]; ZoneLevel.MID -> HallColors.SeatScale[2]; ZoneLevel.HOT -> HallColors.SeatScale[4] })
            Spacer(Modifier.width(10.dp))
            Text(text = r.zoneName(zone) + " · " + r.getString(R.string.seconds, s.zoneDwell.getOrElse(zone) { 0f }.roundToInt()), style = HallType.sign, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        }
    }
}
