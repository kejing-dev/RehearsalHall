package io.embodit.rehearsalhall.platform

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The microphone (design `voiceModel`): AudioRecord 16 kHz mono on its own thread, one RMS value (dBFS) every
 * 100 ms handed to [onFrame]. Nothing is recorded or kept but those numbers. [start] returns false when the
 * recorder cannot be opened (no permission, busy, emulator without input): the report then says "no signal".
 * Every start has its stop (pause, end, Stage closed).
 */
class Mic(private val onFrame: (Float) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, rate / 5)) }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            HallLog.w("MIC init failed state=${r.state}")
            return false
        }
        runCatching { r.startRecording() }.onFailure {
            r.release()
            HallLog.w("MIC start failed: $it")
            return false
        }
        record = r
        running = true
        thread =
            Thread({
                val frame = ShortArray(rate / 10)
                while (running) {
                    var got = 0
                    while (got < frame.size && running) {
                        val n = r.read(frame, got, frame.size - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got <= 0) continue
                    var sum = 0.0
                    for (i in 0 until got) sum += frame[i].toDouble() * frame[i]
                    val rms = sqrt(sum / got) / 32768.0
                    onFrame((20 * log10(max(rms, 1e-5))).toFloat())
                }
            }, "hall-mic").also { it.start() }
        HallLog.i("MIC started")
        return true
    }

    fun stop() {
        if (!running && record == null) return
        running = false
        runCatching { thread?.join(300) }
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        HallLog.i("MIC stopped")
    }
}
