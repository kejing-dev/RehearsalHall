package io.embodit.rehearsalhall.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.embodit.rehearsalhall.domain.Deck
import io.embodit.rehearsalhall.domain.Session
import io.embodit.rehearsalhall.domain.Voice
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything on disk (design `dataModel.storage`), offline, app-private: `decks/<id>/page-NN.png` + `deck.json`,
 * `sessions/<id>.json` (the newest 20 per deck). The sample deck is not stored: its pages are drawn by
 * [SampleDeck]. Called from the main thread for small JSON files and from IO for pages.
 */
class Store(private val context: Context) {
    private val decksDir = File(context.filesDir, "decks").also { it.mkdirs() }
    private val sessionsDir = File(context.filesDir, "sessions").also { it.mkdirs() }

    fun deckDir(id: String): File = File(decksDir, id)

    fun pageFile(id: String, page: Int): File = File(deckDir(id), String.format(java.util.Locale.US, "page-%02d.png", page + 1))

    fun loadDecks(): List<Deck> =
        (decksDir.listFiles() ?: emptyArray())
            .mapNotNull { dir ->
                val f = File(dir, "deck.json")
                if (!f.exists()) {
                    // a half-written import (process killed while importing, design stateModel.background)
                    dir.deleteRecursively()
                    return@mapNotNull null
                }
                runCatching { deckFrom(JSONObject(f.readText())) }.getOrNull()
            }
            .sortedByDescending { it.createdAt }

    fun saveDeck(d: Deck) {
        val dir = deckDir(d.id).also { it.mkdirs() }
        File(dir, "deck.json").writeText(deckJson(d).toString())
    }

    fun deleteDeck(id: String) {
        deckDir(id).deleteRecursively()
        for (s in loadSessions()) if (s.deckId == id) File(sessionsDir, s.id + ".json").delete()
    }

    fun pageBitmap(id: String, page: Int): Bitmap? = runCatching { BitmapFactory.decodeFile(pageFile(id, page).absolutePath) }.getOrNull()

    fun loadSessions(): List<Session> =
        (sessionsDir.listFiles() ?: emptyArray()).mapNotNull { f -> runCatching { sessionFrom(JSONObject(f.readText())) }.getOrNull() }.sortedByDescending { it.startedAt }

    fun saveSession(s: Session) {
        File(sessionsDir, s.id + ".json").writeText(sessionJson(s).toString())
        // keep the newest 20 per deck
        loadSessions().filter { it.deckId == s.deckId }.drop(KEEP_PER_DECK).forEach { File(sessionsDir, it.id + ".json").delete() }
    }

    fun clearSessions() {
        sessionsDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val KEEP_PER_DECK = 20

        fun deckJson(d: Deck): JSONObject =
            JSONObject()
                .put("id", d.id).put("title", d.title).put("source", d.source).put("pageCount", d.pageCount)
                .put("notes", JSONArray(d.notes)).put("targetMinutes", d.targetMinutes).put("createdAt", d.createdAt)

        fun deckFrom(o: JSONObject): Deck {
            val notes = o.optJSONArray("notes")
            return Deck(
                id = o.getString("id"),
                title = o.getString("title"),
                source = o.getString("source"),
                pageCount = o.getInt("pageCount"),
                notes = List(notes?.length() ?: 0) { notes!!.getString(it) },
                targetMinutes = o.optInt("targetMinutes", 3),
                createdAt = o.optLong("createdAt"),
            )
        }

        private fun floats(a: List<Float>) = JSONArray().also { arr -> a.forEach { arr.put(it.toDouble()) } }

        private fun floatsFrom(a: JSONArray?) = List(a?.length() ?: 0) { a!!.getDouble(it).toFloat() }

        fun sessionJson(s: Session): JSONObject {
            val o = JSONObject()
                .put("id", s.id).put("deckId", s.deckId).put("scene", "hall").put("startedAt", s.startedAt)
                .put("durationSec", s.durationSec.toDouble()).put("targetSec", s.targetSec)
                .put("pageTimes", floats(s.pageTimes)).put("zoneDwell", floats(s.zoneDwell)).put("seatDwell", floats(s.seatDwell))
                .put("classTotals", floats(s.classTotals)).put("samples", s.samples)
            when (val v = s.voice) {
                null -> o.put("voice", JSONObject.NULL)
                Voice.NoSignal -> o.put("voice", JSONObject().put("noSignal", true))
                is Voice.Metrics ->
                    o.put(
                        "voice",
                        JSONObject().put("speakRatio", v.speakRatio.toDouble()).put("longestPauseSec", v.longestPauseSec.toDouble())
                            .put("longestPausePage", v.longestPausePage).put("pauseCount", v.pauseCount).put("level", if (v.quiet) "quiet" else "normal"),
                    )
            }
            return o
        }

        fun sessionFrom(o: JSONObject): Session {
            val v = o.optJSONObject("voice")
            val voice: Voice? =
                when {
                    v == null -> null
                    v.optBoolean("noSignal") -> Voice.NoSignal
                    else -> Voice.Metrics(v.getDouble("speakRatio").toFloat(), v.getDouble("longestPauseSec").toFloat(), v.optInt("longestPausePage"), v.optInt("pauseCount"), v.optString("level") == "quiet")
                }
            return Session(
                id = o.getString("id"),
                deckId = o.getString("deckId"),
                startedAt = o.getLong("startedAt"),
                durationSec = o.getDouble("durationSec").toFloat(),
                targetSec = o.getInt("targetSec"),
                pageTimes = floatsFrom(o.optJSONArray("pageTimes")),
                zoneDwell = floatsFrom(o.optJSONArray("zoneDwell")),
                seatDwell = floatsFrom(o.optJSONArray("seatDwell")),
                classTotals = floatsFrom(o.optJSONArray("classTotals")),
                samples = o.optString("samples"),
                voice = voice,
            )
        }
    }
}
