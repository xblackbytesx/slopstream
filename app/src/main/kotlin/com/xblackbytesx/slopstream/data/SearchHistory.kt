package com.xblackbytesx.slopstream.data

import android.content.Context
import com.xblackbytesx.slopstream.ui.components.matchesWords
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One remembered search. [global]: it also searched the service itself ("Also search YouTube"). */
@Serializable
data class PastSearch(
    val source: String,
    val query: String,
    val global: Boolean = false,
    val at: Long = 0,
)

/**
 * What was searched, per service (decision 32), newest first, so a search can be run again
 * with one press. On this device only: a private file, never synced, never sent anywhere.
 * [SearchHistoryRules] decides what is kept and what is suggested.
 */
@Singleton
class SearchHistory(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "search-history.json"))

    private val json = Json { ignoreUnknownKeys = true }
    private val writes = Mutex()
    private val _all = MutableStateFlow<List<PastSearch>>(emptyList())
    /** Every service's, newest first. */
    val all: StateFlow<List<PastSearch>> = _all.asStateFlow()

    init {
        scope.launch {
            val saved = runCatching { json.decodeFromString<List<PastSearch>>(file.readText()) }.getOrDefault(emptyList())
            // Anything recorded while the file was read comes first.
            _all.update { current -> SearchHistoryRules.merge(current, saved) }
        }
    }

    fun record(source: String, query: String, global: Boolean) {
        _all.update { SearchHistoryRules.record(it, source, query, global, clock()) }
        save()
    }

    fun remove(source: String, query: String) {
        _all.update { SearchHistoryRules.remove(it, source, query) }
        save()
    }

    fun clear(source: String) {
        _all.update { list -> list.filterNot { it.source == source } }
        save()
    }

    private fun save() {
        val snapshot = _all.value
        scope.launch { writes.withLock { runCatching { file.writeText(json.encodeToString(snapshot)) } } }
    }
}

/** The rules of [SearchHistory], pure so they are tested. */
object SearchHistoryRules {
    /** Per service. */
    const val KEEP = 30

    /** Shown while typing: few enough to read at a glance and reach with a remote. */
    const val SUGGEST = 6

    /** The same search, ignoring case and spacing. */
    fun key(query: String) = query.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * [query] moves to the front of its service's list, a repeat replaced rather than added. Once
     * searched beyond the library, an entry keeps doing so: running it again finds the same.
     */
    fun record(list: List<PastSearch>, source: String, query: String, global: Boolean, at: Long): List<PastSearch> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.length < MIN_LENGTH) return list
        val old = list.firstOrNull { it.source == source && key(it.query) == key(q) }
        val entry = PastSearch(source, q, global || old?.global == true, at)
        val rest = list.filterNot { it === old }
        val mine = rest.filter { it.source == source }.take(KEEP - 1)
        return listOf(entry) + mine + rest.filter { it.source != source }
    }

    fun remove(list: List<PastSearch>, source: String, query: String) =
        list.filterNot { it.source == source && key(it.query) == key(query) }

    /** [newer] first; an older copy of the same search goes. */
    fun merge(newer: List<PastSearch>, older: List<PastSearch>): List<PastSearch> {
        val seen = HashSet<Pair<String, String>>()
        return (newer + older).filter { seen.add(it.source to key(it.query)) }
    }

    /**
     * What to offer under the search field for [query] in [source]. Empty field: the recent ones.
     * Typing: those that contain every typed word (as library search matches), the one typed
     * exactly too, so the list a press just ran stays put under the remote's focus.
     */
    fun suggestions(list: List<PastSearch>, source: String, query: String): List<PastSearch> {
        val mine = list.filter { it.source == source }
        val q = query.trim()
        if (q.isEmpty()) return mine
        if (mine.any { key(it.query) == key(q) }) return mine
        return mine.filter { matchesWords(it.query, q) }.take(SUGGEST)
    }

    const val MIN_LENGTH = 2
}
