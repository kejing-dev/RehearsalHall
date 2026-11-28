package io.embodit.rehearsalhall.debug

import android.content.Context
import io.embodit.rehearsalhall.domain.Zones
import io.embodit.rehearsalhall.platform.HallLog
import io.embodit.rehearsalhall.ui.hall.HallController
import io.embodit.rehearsalhall.ui.hall.HallEvent
import io.embodit.rehearsalhall.ui.hall.LanguagePref
import io.embodit.rehearsalhall.ui.hall.ReportTab
import io.embodit.rehearsalhall.ui.hall.debug.Probe
import io.embodit.rehearsalhall.ui.hall.components.TextFit
import java.io.File

/**
 * Test hooks of the debug build (design `verification.debugHooks`). Every command answers with one log line
 * `CMD <name> -> <result>` (tag `RehearsalHall`); the state line `STATE S06+M3 ...` follows every change.
 *
 *   state S01 ... S15 | state name <label> | state clear     go to a base state / name override for screenshots
 *   setHead <yaw> <pitch> | setHead off      head direction, hall-local degrees (+ right, + up); off = real head
 *   eye off|on                                force the head-direction fallback / use the eyes when available
 *   setZone <0..8>                            setHead to the centre of a zone (pitch from its head row)
 *   page next|prev|<n>                        turn the page (n is 1-based)
 *   start | pause | resume | end | endok | keep   T1 / T9 / T10
 *   fakeSession <seed> [open]                 a synthetic session of the current deck (open = show its report)
 *   fakeVoice <speakRatio> <pauseSec> <dBFS>  the next report's voice numbers
 *   mic on|off|nosignal                       microphone switch / "no signal"
 *   driftAfter <sec>                          shortens the 60 s drift timers
 *   countdown hold|run                        S05 holds at "2" (screenshots) / runs
 *   importPath <file>[|<file>]                imports without the picker (.pdf / images / .pptx / .docx; "x.pptx|x.pdf" = PPT notes + PDF pictures)
 *   importPictures <deck> <pdf> | importNotes <deck> <txt/pptx/docx>   the deck row ⋯ actions
 *   target <min> | deck sample|none|<id>      target length / select a deck
 *   prompter lectern|front|air|back | lectern low|mid|high | spot on|off   settings (F1)
 *   files | openFile <path>                   the "本机文件" page / open one of its rows
 *   tab gaze|time|voice | replay | done | again
 *   lang system|zh|en | drift on|off | replace
 *   firstRun reset | killFlag                 M5 again / simulate a kill during a talk (M8 on next start)
 *   dump | pose | panels | audience | fit | fitreset (OVERFLOW summary, D13)
 */
object DebugCommands {
    fun run(context: Context, cmd: String, s: String?, a: String?) {
        val result = runCatching { exec(context, cmd.lowercase(), s?.trim(), a?.trim()) }.getOrElse { "error: $it" }
        HallLog.i("CMD $cmd -> $result")
    }

    private fun scene() = Probe.host?.scene()

    private fun on(word: String?): Boolean = word == null || word == "on" || word == "1" || word == "true"

    private fun exec(context: Context, cmd: String, s: String?, a: String?): String {
        val c = HallController
        val args = listOfNotNull(s, a).joinToString(" ").split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        when (cmd) {
            "state" -> {
                val id = args.firstOrNull() ?: return "need id"
                when (id.lowercase()) {
                    "clear" -> c.debugName(null)
                    "name" -> c.debugName(args.drop(1).joinToString(" "))
                    else -> c.debugState(id)
                }
            }
            "sethead" -> {
                if (args.firstOrNull() == "off") scene()?.debugHead = null
                else scene()?.debugHead = args[0].toFloat() to args[1].toFloat()
            }
            "eye" -> scene()?.debugEye(args.firstOrNull() == "off")
            "setzone" -> {
                val z = args[0].toInt().coerceIn(0, Zones.COUNT - 1)
                scene()?.debugHead = Zones.centre(z, c.eyeH)
                return "zone $z -> ${scene()?.debugHead}"
            }
            "page" -> when (val w = args.firstOrNull()) {
                "next" -> c.onEvent(HallEvent.NextPage)
                "prev" -> c.onEvent(HallEvent.PrevPage)
                null -> return "need page"
                else -> c.debugPage(w.toInt() - 1)
            }
            "start" -> c.onEvent(HallEvent.Start)
            "pause" -> c.onEvent(HallEvent.Pause)
            "resume" -> c.onEvent(HallEvent.Resume)
            "end" -> c.onEvent(HallEvent.End)
            "endok" -> c.onEvent(HallEvent.EndConfirmed)
            "keep" -> c.onEvent(HallEvent.KeepTalking)
            "fakesession" -> {
                val seed = args.firstOrNull()?.toIntOrNull() ?: 1
                c.debugFakeSession(seed, open = args.getOrNull(1) == "open")
            }
            "fakevoice" -> c.debugFakeVoice(args[0].toFloat(), args[1].toFloat(), args[2].toFloat())
            "mic" -> c.debugMic(args.firstOrNull() ?: "on")
            "driftafter" -> c.driftAfterSec = args[0].toFloat()
            "countdown" -> c.holdCountdown = args.firstOrNull() == "hold"
            "importpath" -> {
                // several files (a .pptx and its PDF) separated by "|"
                val fs = args.joinToString(" ").split('|').map { File(it.trim()) }
                fs.firstOrNull { !it.exists() }?.let { return "missing ${it.path}" }
                // copy out of shared storage first, like the picker does
                val dir = File(context.cacheDir, "import/debug").also { it.deleteRecursively(); it.mkdirs() }
                c.importFiles(fs.map { f -> File(dir, f.name).also { f.copyTo(it, overwrite = true) } })
            }
            "importpictures", "importnotes" -> {
                val f = File(args.drop(1).joinToString(" "))
                if (!f.exists()) return "missing ${f.path}"
                val out = File(File(context.cacheDir, "import/debug2").also { it.deleteRecursively(); it.mkdirs() }, f.name)
                f.copyTo(out, overwrite = true)
                if (cmd == "importpictures") c.importPictures(args[0], out) else c.debugImportNotes(args[0], out)
            }
            "target" -> c.debugTarget(args[0].toInt())
            "prompter" -> c.onEvent(HallEvent.SetPrompter(io.embodit.rehearsalhall.domain.PrompterPlace.valueOf(args[0].uppercase())))
            "lectern" -> c.onEvent(HallEvent.SetLecternHeight(io.embodit.rehearsalhall.domain.LecternHeight.valueOf(args[0].uppercase())))
            "spot" -> c.onEvent(HallEvent.Spot(args.firstOrNull() != "off"))
            "storyline" -> c.onEvent(HallEvent.SetStoryline(args.firstOrNull() != "off"))
            "files" -> c.onEvent(HallEvent.OpenFiles)
            "openfile" -> c.onEvent(HallEvent.OpenFile(args.joinToString(" ")))
            "deck" -> c.onEvent(HallEvent.PickDeck(args[0]))
            "tab" -> c.onEvent(HallEvent.Tab(ReportTab.valueOf(args[0].uppercase())))
            "replay" -> c.onEvent(HallEvent.Replay)
            "done" -> c.onEvent(HallEvent.Done)
            "again" -> c.onEvent(HallEvent.Again)
            "back" -> c.onEvent(HallEvent.Back)
            "lang" -> c.onEvent(HallEvent.Language(LanguagePref.valueOf(args[0].uppercase())))
            "drift" -> c.onEvent(HallEvent.Drift(on(args.firstOrNull())))
            "replace" -> c.onEvent(HallEvent.Replace)
            "firstrun" -> c.debugFirstRunReset()
            "killflag" -> c.debugKillFlag()
            "dump" -> return c.dump()
            "fit" -> return TextFit.summary()
            "fitreset" -> { TextFit.reset(); return "FIT reset" }
            "pose" -> return scene()?.poseLine() ?: "no scene"
            "audience" -> return scene()?.audienceLine() ?: "no scene"
            "panels" -> {
                scene()?.panelLines()?.forEach { HallLog.i(it) }
                return "logged"
            }
            else -> return "unknown command"
        }
        return "ok ${c.designState()}"
    }
}
