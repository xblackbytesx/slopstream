package com.xblackbytesx.slopstream.data

import android.content.Context
import com.xblackbytesx.slopstream.youtube.innertube.ResolveReport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The experimental extractor's switch and its record of plays (decision 30): the last [KEEP]
 * plays, kept in a file in the app's private storage so a session on the TV can be read back
 * later. Video ids and timings only; never sent anywhere.
 */
@Singleton
class ExtractorLog @Inject constructor(
    @ApplicationContext context: Context,
    settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(context.filesDir, "extractor-log.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** Read on every play, so it must be hot: the setting, as last seen. */
    val enabled: StateFlow<Boolean> = settings.settings.map { it.ownExtractor }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _reports = MutableStateFlow<List<ResolveReport>>(emptyList())
    /** Newest first. */
    val reports: StateFlow<List<ResolveReport>> = _reports.asStateFlow()

    init {
        scope.launch {
            val saved = runCatching { json.decodeFromString<List<Entry>>(file.readText()) }.getOrDefault(emptyList())
            _reports.update { current -> (current + saved.map { it.toReport() }).take(KEEP) }
        }
    }

    fun add(report: ResolveReport) {
        _reports.update { (listOf(report) + it).take(KEEP) }
        save()
    }

    fun clear() {
        _reports.value = emptyList()
        save()
    }

    private fun save() {
        val snapshot = _reports.value.map(Entry::from)
        scope.launch { runCatching { file.writeText(json.encodeToString(snapshot)) } }
    }

    // Not private: kotlinx-serialization resolves the serializer at the call site, and ART
    // enforces access there (a private type or companion fails on the device only).
    @Serializable
    internal data class Entry(
        val videoId: String,
        val at: Long,
        val kind: String,
        val ownMs: Long,
        val ownPath: String? = null,
        val ownError: String? = null,
        val ownSteps: List<String> = emptyList(),
        val newpipeMs: Long? = null,
        val newpipeError: String? = null,
        val title: String = "",
    ) {
        fun toReport() = ResolveReport(videoId, at, kind, ownMs, ownPath, ownError, ownSteps, newpipeMs, newpipeError, title)

        companion object {
            fun from(r: ResolveReport) = Entry(r.videoId, r.at, r.kind, r.ownMs, r.ownPath, r.ownError, r.ownSteps, r.newpipeMs, r.newpipeError, r.title)
        }
    }

    companion object {
        const val KEEP = 200
    }
}

/** The log in a few numbers, for the top of its screen. Pure, so it is tested. */
data class ExtractorStats(
    val plays: Int,
    val ours: Int,
    val rescued: Int,
    val fallbacks: Int,
    val failed: Int,
    val oursMedianMs: Long?,
    val fallbackMedianMs: Long?,
    val paths: Map<String, Int>,
) {
    companion object {
        fun of(reports: List<ResolveReport>): ExtractorStats {
            fun median(v: List<Long>) = v.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            val ours = reports.filter { it.ownOk }
            val fallbacks = reports.filter { !it.ownOk && it.newpipeMs != null }
            return ExtractorStats(
                plays = reports.size,
                ours = ours.size,
                // A first answer refused deep: a new visitor or the HLS stream saved it.
                rescued = ours.count { it.ownPath == "hls" || it.ownPath.orEmpty().endsWith("-new-visitor") },
                fallbacks = fallbacks.count { it.played },
                failed = reports.count { !it.played },
                oursMedianMs = median(ours.map { it.ownMs }),
                // What a fallback costs in all: ours trying, then NewPipe.
                fallbackMedianMs = median(fallbacks.map { it.ownMs + (it.newpipeMs ?: 0) }),
                paths = ours.groupingBy { it.ownPath.orEmpty() }.eachCount(),
            )
        }
    }
}
