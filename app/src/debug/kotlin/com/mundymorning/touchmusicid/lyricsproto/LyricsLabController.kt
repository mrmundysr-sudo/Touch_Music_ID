package com.mundymorning.touchmusicid.lyricsproto

import android.content.Context
import com.mundymorning.touchmusicid.BuildConfig
import com.mundymorning.touchmusicid.domain.songKey
import com.mundymorning.touchmusicid.net.UrlConnectionFetcher
import com.mundymorning.touchmusicid.recognition.RemoteTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ProbeMode { CONTINUOUS, PERIODIC }

data class SongInfo(
    val title: String, val artist: String, val isrc: String?, val appleMusicId: String?,
    val album: String? = null, val durationMs: Long? = null, val metaNote: String = "",
)

sealed interface LyricsState {
    data object Idle : LyricsState
    data class Loading(val step: String) : LyricsState
    /** [doc] is non-null only for trustworthy synchronized lyrics; otherwise [lines] is a plain, manually scrollable sheet. */
    data class Ready(val lines: List<String>, val doc: LrcDoc?, val info: String) : LyricsState {
        val synced: Boolean get() = doc != null
    }
    data class NotFound(val info: String) : LyricsState
    data class Failed(val message: String) : LyricsState
    data object Instrumental : LyricsState
}

data class LabState(
    val running: Boolean = false,
    val status: String = "Idle",
    val song: SongInfo? = null,
    val lyrics: LyricsState = LyricsState.Idle,
    val follow: Boolean = true,
    val mode: ProbeMode = ProbeMode.CONTINUOUS,
    val estimator: Estimator = Estimator.B,
    val nudgeMs: Int = 0,
    val logPath: String? = null,
    val sdkAvailable: Boolean = true,
)

data class TickInfo(
    val positionMs: Long = -1,
    val activeIdx: Int = -1,
    val confidence: Double = 0.0,
    val syncState: SyncState = SyncState.SEARCHING,
    val rms: Double = 0.0,
)

interface LabPrefs {
    var nudgeMs: Int
    var estimator: String
    var mode: String
    var follow: Boolean
}

class MemoryLabPrefs : LabPrefs {
    override var nudgeMs: Int = 0
    override var estimator: String = Estimator.B.name
    override var mode: String = ProbeMode.CONTINUOUS.name
    override var follow: Boolean = true
}

class SharedLabPrefs(context: Context) : LabPrefs {
    private val p = context.getSharedPreferences("lyricslab", Context.MODE_PRIVATE)
    override var nudgeMs: Int
        get() = p.getInt("nudgeMs", 0)
        set(v) { p.edit().putInt("nudgeMs", v).apply() }
    override var estimator: String
        get() = p.getString("estimator", Estimator.B.name) ?: Estimator.B.name
        set(v) { p.edit().putString("estimator", v).apply() }
    override var mode: String
        get() = p.getString("mode", ProbeMode.CONTINUOUS.name) ?: ProbeMode.CONTINUOUS.name
        set(v) { p.edit().putString("mode", v).apply() }
    override var follow: Boolean
        get() = p.getBoolean("follow", true)
        set(v) { p.edit().putBoolean("follow", v).apply() }
}

/**
 * Orchestrates the Lyrics Lab: recognition events -> position estimates -> sync policy -> lyric highlighting, plus the
 * metadata/LRCLIB pipeline and a CSV log. All mutable state is touched on the scope's (main) dispatcher.
 * Silence detection is a heuristic for "playback paused" and is labelled as such everywhere.
 */
class LyricsLabController(
    private val scope: CoroutineScope,
    private val probe: MatchProbe?,
    private val tokensConfigured: Boolean,
    private val lrclib: LrclibClient,
    private val itunes: ITunesClient,
    private val musicBrainz: MusicBrainzClient,
    private val prefs: LabPrefs = MemoryLabPrefs(),
    private val logDir: File? = null,
    private val deviceInfo: String = "",
    private val clock: () -> Double = { System.nanoTime() / 1e6 },
    private val epochClock: () -> Long = { System.currentTimeMillis() },
) {
    val sync = SyncController()

    private val _state = MutableStateFlow(
        LabState(
            follow = prefs.follow,
            mode = runCatching { ProbeMode.valueOf(prefs.mode) }.getOrDefault(ProbeMode.CONTINUOUS),
            estimator = runCatching { Estimator.valueOf(prefs.estimator) }.getOrDefault(Estimator.B),
            nudgeMs = prefs.nudgeMs,
            sdkAvailable = probe != null,
        ),
    )
    val state: StateFlow<LabState> = _state.asStateFlow()
    private val _tick = MutableStateFlow(TickInfo())
    val tick: StateFlow<TickInfo> = _tick.asStateFlow()
    private val _diag = MutableStateFlow<List<String>>(emptyList())
    val diag: StateFlow<List<String>> = _diag.asStateFlow()

    private val events = Channel<ProbeEvent>(Channel.UNLIMITED)
    private val fixSignal = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    private val timelines = HashMap<Int, CaptureTimeline>()
    private var probeJob: Job? = null
    private var consumerJob: Job? = null
    private var tickJob: Job? = null
    private var lyricsJob: Job? = null
    private var sessionCounter = 0
    private var log: CsvLog? = null

    private var lastRaw: RawFix? = null
    private var lastEst: Estimates? = null
    private var songRaw: RawFix? = null
    private var currentKey: String? = null
    private var pendingKey: String? = null
    private var pendingKeyCount = 0
    private var lastRms = 0.0
    private var lastDriftMs: Double? = null
    private var timelineMethod = ""
    private var quietStart: Double? = null
    private var soundStart: Double? = null
    private var rmsAcc = 0.0
    private var rmsN = 0
    private var lastRmsLog = 0.0

    init { sync.clock.nudgeMs = prefs.nudgeMs.toDouble() }

    // ------------------------------------------------------------------ controls

    fun start() {
        if (_state.value.running) return
        val p = probe
        if (p == null) { setStatus("ShazamKit SDK is not linked in this build"); return }
        if (!tokensConfigured) { setStatus("Token service URL is missing in this build"); return }
        openLog()
        _state.update { it.copy(running = true, status = "Listening… play music near the phone") }
        consumerJob = scope.launch { for (e in events) handle(e) }
        tickJob = scope.launch { tickLoop() }
        launchProbe(p)
    }

    fun stop() {
        probeJob?.cancel(); probeJob = null
        consumerJob?.cancel(); consumerJob = null
        tickJob?.cancel(); tickJob = null
        while (events.tryReceive().isSuccess) { /* drain */ }
        if (_state.value.running) csv("STOP", emptyMap())
        log?.flush(); log?.close(); log = null
        _state.update { it.copy(running = false, status = if (it.song != null) "Stopped" else "Idle") }
    }

    fun close() { stop(); lyricsJob?.cancel() }

    /** Take a fresh recognition window and replace the anchor with it (no damping). */
    fun resyncNow() {
        val p = probe ?: return
        if (!_state.value.running) return
        sync.requireFreshFix()
        csv("RESYNC_REQUEST", emptyMap())
        probeJob?.cancel()
        launchProbe(p)
        setStatus("Re-syncing…")
    }

    fun setMode(m: ProbeMode) {
        if (m == _state.value.mode) return
        prefs.mode = m.name
        _state.update { it.copy(mode = m) }
        csv("MODE", mapOf("mode" to m.name))
        val p = probe
        if (_state.value.running && p != null) { probeJob?.cancel(); launchProbe(p) }
    }

    fun setEstimator(e: Estimator) {
        if (e == _state.value.estimator) return
        prefs.estimator = e.name
        _state.update { it.copy(estimator = e) }
        csv("ESTIMATOR", mapOf("estimator" to e.name))
        // Re-anchor from the most recent fix with the newly chosen assumption, so the three can be compared live.
        val raw = lastRaw; val est = lastEst
        val pos = est?.pick(e)
        if (raw != null && pos != null && currentKey != null) {
            sync.requireFreshFix()
            val ev = sync.onFix(PositionFix(pos, raw.readMonoMs, Positioning.rate(raw.frequencySkew), e.name))
            csvSync(ev)
        }
    }

    fun nudge(deltaMs: Int) {
        sync.nudge(deltaMs.toDouble())
        val total = sync.clock.nudgeMs.toInt()
        prefs.nudgeMs = total
        _state.update { it.copy(nudgeMs = total) }
        csv("NUDGE", mapOf("deltaMs" to deltaMs, "totalMs" to total))
    }

    fun resetNudge() {
        sync.clock.nudgeMs = 0.0
        prefs.nudgeMs = 0
        _state.update { it.copy(nudgeMs = 0) }
        csv("NUDGE", mapOf("deltaMs" to 0, "totalMs" to 0, "reset" to true))
    }

    fun toggleFollow() {
        val f = !_state.value.follow
        prefs.follow = f
        _state.update { it.copy(follow = f) }
    }

    /** Writes the current estimates to the log, to be matched against a reference (e.g. a video of a laptop player's timecode). */
    fun mark() {
        val now = clock()
        csv("MARK", mapOf("pos" to sync.clock.positionMs(now)?.toLong()) + estimatesNow(now))
        setStatus("Mark saved")
    }

    fun retryLyrics() {
        val raw = songRaw ?: return
        lyricsJob?.cancel()
        lyricsJob = scope.launch { fetchLyrics(raw) }
    }

    fun diagText(): String = _diag.value.joinToString("\n") + "\n" + sync.events.takeLast(15).joinToString("\n") {
        "${it.kind} diff=${it.diffMs.toLong()} applied=${it.appliedMs.toLong()} ${it.note}"
    }

    private fun setStatus(s: String) { _state.update { it.copy(status = s) } }

    // ------------------------------------------------------------------ recognition

    private fun launchProbe(p: MatchProbe) {
        probeJob = scope.launch {
            val emit: (ProbeEvent) -> Unit = { events.trySend(it) }
            try {
                when (_state.value.mode) {
                    ProbeMode.CONTINUOUS -> p.run(++sessionCounter, emit)
                    ProbeMode.PERIODIC -> coroutineScope {
                        while (isActive) {
                            val sid = ++sessionCounter
                            val window = launch { p.run(sid, emit) }
                            withTimeoutOrNull(WINDOW_MS) { fixSignal.first { it == sid } }
                            window.cancelAndJoin()
                            delay(PERIOD_MS)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(ProbeEvent.Failed(sessionCounter, e.message ?: e.javaClass.simpleName))
            }
            if (_state.value.mode == ProbeMode.CONTINUOUS && isActive) _state.update { it.copy(running = false) }
        }
    }

    private fun handle(e: ProbeEvent) {
        when (e) {
            is ProbeEvent.Started -> {
                timelines[e.sessionId] = e.timeline
                timelineMethod = e.timeline.method
                csv("SESSION", mapOf("session" to e.sessionId, "frame0EpochMs" to e.timeline.frame0EpochMs, "frame0MonoMs" to e.timeline.frame0MonoMs, "method" to e.timeline.method))
            }
            is ProbeEvent.Audio -> onAudio(e)
            is ProbeEvent.Fix -> onFix(e.raw)
            is ProbeEvent.NoMatch -> {
                csv("NOMATCH", mapOf("session" to e.sessionId, "sigDurationMs" to e.sigDurationMs))
                if (currentKey == null) setStatus("Listening… no match yet")
            }
            is ProbeEvent.Failed -> { csv("ERROR", mapOf("session" to e.sessionId, "message" to e.message)); setStatus("Problem: ${e.message}") }
            is ProbeEvent.Info -> csv("INFO", mapOf("session" to e.sessionId, "message" to e.message))
        }
    }

    private fun onAudio(e: ProbeEvent.Audio) {
        lastRms = e.rms
        lastDriftMs = e.driftMs
        rmsAcc += e.rms; rmsN++
        if (e.monoMs - lastRmsLog >= 1000) {
            csv("RMS", mapOf("rms" to "%.5f".format(Locale.ROOT, if (rmsN > 0) rmsAcc / rmsN else 0.0), "peak" to e.peak, "driftMs" to e.driftMs?.let { "%.1f".format(Locale.ROOT, it) }))
            rmsAcc = 0.0; rmsN = 0; lastRmsLog = e.monoMs
        }
        // Heuristic only: sustained quiet may be a pause, or just a quiet passage of the song.
        if (e.rms < SILENCE_RMS) {
            if (quietStart == null) quietStart = e.monoMs
            soundStart = null
            val qs = quietStart
            if (qs != null && e.monoMs - qs >= SILENCE_HOLD_MS && sync.state == SyncState.FOLLOWING) {
                sync.onSilenceStart(qs)
                csv("HOLD", mapOf("quietSinceMono" to qs, "note" to "silence heuristic"))
            }
        } else {
            quietStart = null
            if (sync.state == SyncState.HOLD) {
                if (soundStart == null) soundStart = e.monoMs
                val ss = soundStart
                if (ss != null && e.monoMs - ss >= SOUND_RESUME_MS) {
                    sync.onSoundResume(e.monoMs)
                    soundStart = null
                    csv("RESUME", mapOf("note" to "sound returned; waiting for a fresh fix"))
                }
            } else soundStart = null
        }
    }

    private fun onFix(raw: RawFix) {
        val tl = timelines[raw.sessionId] ?: return
        val est = Positioning.estimate(raw, tl)
        lastRaw = raw
        lastEst = est
        csv(
            "FIX",
            mapOf(
                "session" to raw.sessionId, "title" to raw.title, "artist" to raw.artist, "isrc" to raw.isrc, "appleId" to raw.appleMusicId,
                "matchOffsetMs" to raw.matchOffsetMs, "predictedMs" to raw.predictedMs, "freqSkew" to raw.frequencySkew,
                "sigDurationMs" to raw.sigDurationMs, "items" to raw.itemCount, "readMono" to raw.readMonoMs, "readEpoch" to raw.readEpochMs,
                "lastFedEndMono" to raw.lastFedEndMono, "lastFedEndStreamMs" to raw.lastFedEndStreamMs,
                "frame0Mono" to tl.frame0MonoMs, "frame0Epoch" to tl.frame0EpochMs, "timeline" to tl.method,
                "estA" to est.a?.toLong(), "estB" to est.b?.toLong(), "estC" to est.c?.toLong(),
            ),
        )
        fixSignal.tryEmit(raw.sessionId)

        val key = songKey(raw.title, raw.artist)
        val cur = currentKey
        if (cur == null) {
            beginSong(raw, key)
        } else if (key != cur) {
            // A single contradicting identification may be a mistake; require two in a row before switching songs.
            if (pendingKey == key) pendingKeyCount++ else { pendingKey = key; pendingKeyCount = 1 }
            csv("SONG_CHANGE_CANDIDATE", mapOf("title" to raw.title, "artist" to raw.artist, "count" to pendingKeyCount))
            if (pendingKeyCount >= 2) beginSong(raw, key) else return
        } else {
            pendingKey = null
        }

        val estimator = _state.value.estimator
        val pos = est.pick(estimator)
        if (pos == null) { csv("NO_ESTIMATE", mapOf("estimator" to estimator.name)); return }
        val ev = sync.onFix(PositionFix(pos, raw.readMonoMs, Positioning.rate(raw.frequencySkew), estimator.name))
        csvSync(ev)
        setStatus("Following (${ev.kind})")
    }

    private fun beginSong(raw: RawFix, key: String) {
        currentKey = key
        pendingKey = null
        pendingKeyCount = 0
        songRaw = raw
        sync.reset()
        _state.update { it.copy(song = SongInfo(raw.title, raw.artist, raw.isrc, raw.appleMusicId), lyrics = LyricsState.Loading("starting")) }
        csv("SONG", mapOf("title" to raw.title, "artist" to raw.artist, "isrc" to raw.isrc, "appleId" to raw.appleMusicId))
        lyricsJob?.cancel()
        lyricsJob = scope.launch { fetchLyrics(raw) }
    }

    // ------------------------------------------------------------------ lyrics pipeline

    private fun setLyrics(l: LyricsState) { _state.update { it.copy(lyrics = l) } }

    private suspend fun fetchLyrics(raw: RawFix) {
        try {
            setLyrics(LyricsState.Loading("duration and album"))
            var durationMs: Long? = null
            var album: String? = null
            val notes = ArrayList<String>()
            val appleId = raw.appleMusicId
            if (appleId != null) {
                when (val r = itunes.lookupById(appleId)) {
                    is MetaResult.Found -> { durationMs = r.meta.durationMs; album = r.meta.album; notes += "iTunes dur=${r.meta.durationMs}ms album=${r.meta.album}" }
                    MetaResult.NotFound -> notes += "iTunes: no entry"
                    is MetaResult.Failed -> notes += "iTunes FAILED(${r.kind})"
                }
            }
            val isrc = raw.isrc
            if (isrc != null) {
                when (val r = musicBrainz.byIsrc(isrc)) {
                    is MetaResult.Found -> {
                        notes += "MusicBrainz ${r.meta.note}"
                        if (durationMs == null) durationMs = r.meta.durationMs
                        if (album == null) album = r.meta.album
                    }
                    MetaResult.NotFound -> notes += "MusicBrainz: ISRC unknown"
                    is MetaResult.Failed -> notes += "MusicBrainz FAILED(${r.kind})"
                }
            }
            val note = notes.joinToString(" | ")
            _state.update { it.copy(song = it.song?.copy(album = album, durationMs = durationMs, metaNote = note)) }
            csv("META", mapOf("durationMs" to durationMs, "album" to album, "notes" to note))

            setLyrics(LyricsState.Loading("lyrics (LRCLIB)"))
            val q = TrackQuery(raw.title, raw.artist, album, durationMs?.let { it / 1000.0 })
            when (val res = lrclib.lookup(q)) {
                is LyricsLookup.Found -> {
                    val c = res.best
                    val rec = c.record
                    val drift = c.durationGapSec?.let { " drift=${"%.1f".format(Locale.ROOT, it)}s" } ?: ""
                    val info = "LRCLIB #${rec.id} \"${rec.trackName}\" score=${"%.2f".format(Locale.ROOT, c.score)}$drift requests=${res.requests}"
                    csv("LRCLIB", mapOf("id" to rec.id, "score" to c.score, "synced" to !rec.synced.isNullOrBlank(), "plain" to !rec.plain.isNullOrBlank(), "durationSec" to rec.durationSec, "candidates" to res.ranked.size))
                    if (rec.instrumental) { setLyrics(LyricsState.Instrumental); return }
                    val doc = LrcParser.parse(rec.synced)
                    val plausible = LyricsSanity.syncedLooksPlausible(doc, durationMs)
                    when {
                        plausible -> setLyrics(LyricsState.Ready(doc.lines.map { it.text }, doc, info))
                        !rec.plain.isNullOrBlank() || !doc.isEmpty -> {
                            val plain = rec.plain?.lines() ?: doc.lines.map { it.text }
                            val why = if (doc.isEmpty) "no synchronized lyrics" else "synchronized lyrics rejected (timestamps run past the track end)"
                            setLyrics(LyricsState.Ready(plain, null, "$info; $why"))
                        }
                        else -> setLyrics(LyricsState.NotFound("$info; record has no lyrics"))
                    }
                }
                is LyricsLookup.NotFound -> {
                    csv("LRCLIB", mapOf("result" to "NOT_FOUND", "candidates" to res.ranked.size, "rejected" to res.ranked.take(3).joinToString("/") { it.rejectedReason.orEmpty() }))
                    setLyrics(LyricsState.NotFound("No LRCLIB match (${res.requests} requests, ${res.ranked.size} candidates rejected)"))
                }
                is LyricsLookup.Failed -> {
                    csv("LRCLIB", mapOf("result" to "FAILED", "kind" to res.kind, "message" to res.message))
                    setLyrics(LyricsState.Failed("${res.kind}: ${res.message}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setLyrics(LyricsState.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    // ------------------------------------------------------------------ ticking, diagnostics, log

    private suspend fun tickLoop() {
        var n = 0
        var lastCsv = 0.0
        while (currentCoroutineContext().isActive) {
            val now = clock()
            sync.evaluate(now)
            val pos = sync.clock.positionMs(now)
            val doc = (_state.value.lyrics as? LyricsState.Ready)?.doc
            val active = if (pos != null && doc != null) LrcParser.findActiveIndex(doc.lines, pos.toLong()) else -1
            _tick.value = TickInfo(pos?.toLong() ?: -1L, active, sync.confidence(now), sync.state, lastRms)
            if (n % 5 == 0) _diag.value = diagLines(now, pos)
            if (pos != null && now - lastCsv >= 500) {
                csv("TICK", mapOf("pos" to pos.toLong(), "state" to sync.state.name, "conf" to "%.2f".format(Locale.ROOT, sync.confidence(now)), "active" to active) + estimatesNow(now))
                lastCsv = now
                log?.flush()
            }
            n++
            delay(50)
        }
    }

    /** Each estimator's current position: its value at read time advanced by the time since. */
    private fun estimatesNow(now: Double): Map<String, Any?> {
        val raw = lastRaw ?: return emptyMap()
        val est = lastEst ?: return emptyMap()
        fun adv(v: Double?) = v?.let { Positioning.advance(it, raw.readMonoMs, now).toLong() }
        return mapOf("nowA" to adv(est.a), "nowB" to adv(est.b), "nowC" to adv(est.c))
    }

    private fun diagLines(now: Double, pos: Double?): List<String> {
        val out = ArrayList<String>()
        val ago = sync.lastVerifiedMono?.let { (now - it) / 1000 } 
        out += "pos=${pos?.toLong() ?: -1} state=${sync.state} conf=${"%.2f".format(Locale.ROOT, sync.confidence(now))} ago=${ago?.let { "%.1f".format(Locale.ROOT, it) } ?: "-"}s"
        out += "est ${_state.value.estimator} nudge=${_state.value.nudgeMs} mode=${_state.value.mode}"
        out += "MIC rms=${"%.4f".format(Locale.ROOT, lastRms)} clockDrift=${lastDriftMs?.let { "%.1f".format(Locale.ROOT, it) }}ms timeline=$timelineMethod"
        return out
    }

    private fun diff(a: Double?, b: Double?): String = if (a == null || b == null) "-" else (a - b).toLong().toString()

    private fun csvSync(ev: SyncEvent) {
        csv("SYNC", mapOf("kind" to ev.kind, "diffMs" to ev.diffMs.toLong(), "appliedMs" to ev.appliedMs.toLong(), "note" to ev.note, "state" to sync.state.name))
    }

    private fun csv(kind: String, fields: Map<String, Any?>) { log?.row(kind, clock(), epochClock(), fields) }

    private fun openLog() {
        val dir = logDir
        if (dir == null) { log = null; return }
        try {
            dir.mkdirs()
            val name = "lyricslab-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(epochClock())) + ".csv"
            val f = File(dir, name)
            log = CsvLog(BufferedWriter(FileWriter(f)))
            _state.update { it.copy(logPath = f.absolutePath) }
            csv("META_DEVICE", mapOf("device" to deviceInfo, "estimator" to _state.value.estimator.name, "mode" to _state.value.mode.name, "nudgeMs" to prefs.nudgeMs))
        } catch (e: java.io.IOException) {
            log = null
            setStatus("Couldn't open the log file: ${e.message}")
        }
    }

    companion object {
        const val SILENCE_RMS = 0.0015
        const val SILENCE_HOLD_MS = 4_000.0
        const val SOUND_RESUME_MS = 300.0
        const val WINDOW_MS = 14_000L
        const val PERIOD_MS = 45_000L

        fun create(context: Context, scope: CoroutineScope): LyricsLabController {
            val tokens = RemoteTokenSource(BuildConfig.TOKEN_SERVICE_URL, UrlConnectionFetcher())
            val capture = LabAudioCapture()
            return LyricsLabController(
                scope = scope,
                probe = ProbeFactory.create(tokens, capture),
                tokensConfigured = tokens.isConfigured,
                lrclib = LrclibClient(LabHttp.lrclib()),
                itunes = ITunesClient(LabHttp.itunes()),
                musicBrainz = MusicBrainzClient(LabHttp.musicBrainz()),
                prefs = SharedLabPrefs(context),
                logDir = context.getExternalFilesDir("lyricslab") ?: File(context.cacheDir, "lyricslab"),
                deviceInfo = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}",
            )
        }
    }
}
