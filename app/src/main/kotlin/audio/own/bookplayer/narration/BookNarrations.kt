package audio.own.bookplayer.narration

import audio.own.book.library.BookNarrator
import audio.own.bookplayer.app.AppLog
import audio.own.kit.ownaudio.NarrationJob
import java.io.File
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A narration started from this phone, as its Library row shows it. */
@Serializable
data class NarrationEntry(
    val id: String,
    val serverId: String,
    val title: String,
    val startedAtMs: Long,
    val stage: String,
    val stages: List<String> = emptyList(),
    val error: String? = null,
    val bookId: String? = null,
    val chaptersDone: Int = 0,
    val chaptersTotal: Int = 0,
    val quotedPriceCents: Int = 0,
    val currency: String = "USD",
) {
    val isTerminal: Boolean get() = stage == "complete" || stage == "failed"

    /** Stages done over stages there are, with the chapters narrated so far inside the one that counts them. */
    val fraction: Float
        get() {
            val index = stages.indexOf(stage)
            if (stages.isEmpty() || index < 0) return 0f
            val within = if (chaptersTotal > 0) chaptersDone.toFloat() / chaptersTotal else 0f
            return ((index + within) / stages.size).coerceIn(0f, 1f)
        }

    fun updated(job: NarrationJob) = copy(
        title = job.title, stage = job.stage, stages = job.stages, error = job.error, bookId = job.bookId,
        chaptersDone = job.chapters.count { it.status == "complete" }, chaptersTotal = job.chapters.size,
    )

    companion object {
        fun of(job: NarrationJob, serverId: String, at: Long) = NarrationEntry(
            id = job.id, serverId = serverId, title = job.title, startedAtMs = at, stage = job.stage, stages = job.stages,
            error = job.error, bookId = job.bookId, chaptersDone = job.chapters.count { it.status == "complete" },
            chaptersTotal = job.chapters.size, quotedPriceCents = job.quotedPriceCents, currency = job.currency,
        )
    }
}

/**
 * Narrations started from this phone (iOS NarrationManager): each job is written to
 * `narrations.json` the moment the server answers, so a kill finds it again; while the app is in
 * front, every running job is asked about every 4 s at first and half as often after each answer
 * that brought nothing, up to a minute, and at once when the live stream says a narration moved.
 * A finished job's book arrives through the catalogue sync.
 */
class BookNarrations(
    private val file: File,
    private val narrator: suspend (serverId: String) -> BookNarrator?,
    private val scope: CoroutineScope,
    private val log: AppLog,
    /** A job finished: the catalogue catches up and the book shows. */
    private val onCompleted: (NarrationEntry) -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
) {
    private val serializer = MapSerializer(String.serializer(), NarrationEntry.serializer())
    private val lock = Mutex()
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, NarrationEntry>> = _entries.asStateFlow()

    private var polling: Job? = null
    private var listening: Job? = null
    private var foreground = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun active(serverId: String): List<NarrationEntry> = _entries.value.values.filter { it.serverId == serverId }.sortedByDescending { it.startedAtMs }

    val hasRunning: Boolean get() = _entries.value.values.any { !it.isTerminal }

    suspend fun add(job: NarrationJob, serverId: String) {
        lock.withLock {
            _entries.update { it + (job.id to NarrationEntry.of(job, serverId, clock.millis())) }
            save()
        }
        startPolling()
    }

    suspend fun forget(id: String) = lock.withLock {
        _entries.update { it - id }
        save()
    }

    /** The app came to the front or went: polling follows. */
    fun setForeground(on: Boolean) {
        foreground = on
        if (on) startPolling() else stopPolling()
    }

    fun startPolling() {
        if (!foreground || polling?.isActive == true || !hasRunning) return
        listen()
        polling = scope.launch {
            var delayMs = FIRST_DELAY_MS
            while (isActive && hasRunning) {
                val changed = pollOnce() ?: break
                delayMs = nextDelay(delayMs, changed)
                withTimeoutOrNull(delayMs) { wake.receive() }?.let { delayMs = FIRST_DELAY_MS }
            }
            listening?.cancel()
        }
    }

    fun stopPolling() {
        polling?.cancel()
        polling = null
        listening?.cancel()
        listening = null
    }

    /** `narration` events on the live stream of the servers with running jobs wake the poller. */
    private fun listen() {
        if (listening?.isActive == true) return
        listening = scope.launch {
            val servers = _entries.value.values.filter { !it.isTerminal }.map { it.serverId }.distinct()
            val flows = servers.mapNotNull { narrator(it)?.narrationEvents }
            if (flows.isNotEmpty()) merge(*flows.toTypedArray()).collect { wake.trySend(Unit) }
        }
    }

    /**
     * Asks about every running job once. Whether anything changed; null when no running job's
     * server is set up any more, so there is nobody to ask.
     */
    internal suspend fun pollOnce(): Boolean? {
        var changed = false
        var asked = false
        for (entry in _entries.value.values.filter { !it.isTerminal }) {
            val source = narrator(entry.serverId) ?: continue
            asked = true
            val job = try {
                source.narration(entry.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("narration", "job ${entry.id}: ${e.message}")
                continue
            }
            val updated = entry.updated(job)
            if (updated == entry) continue
            changed = true
            lock.withLock {
                _entries.update { it + (entry.id to updated) }
                save()
            }
            if (updated.stage == "complete" && entry.stage != "complete") onCompleted(updated)
        }
        return if (asked) changed else null
    }

    private fun load(): Map<String, NarrationEntry> = try {
        if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(serializer, _entries.value))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        const val FIRST_DELAY_MS = 4_000L
        const val LONGEST_DELAY_MS = 60_000L

        /**
         * Four seconds after a change, twice as long after each answer that brought none, up to a
         * minute: a job takes minutes to hours, and a phone pays for a tight loop in battery and data.
         */
        fun nextDelay(afterMs: Long, changed: Boolean): Long = if (changed) FIRST_DELAY_MS else minOf(afterMs * 2, LONGEST_DELAY_MS)

        private val json = Json { ignoreUnknownKeys = true }
    }
}
