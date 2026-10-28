package com.xblackbytesx.slopstream.youtube

import com.xblackbytesx.slopstream.core.ImportedSubscription
import com.xblackbytesx.slopstream.core.SourceKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ImportFormatException(message: String) : Exception(message)

/**
 * Reads subscriptions exported elsewhere. Only YouTube channels with a `UC…` id are kept;
 * handles and legacy `/user/` URLs are returned in [ImportResult.unresolved] so the app can look
 * them up one by one. NewPipe's database zip is read in the app (it needs SQLite) and fed to
 * [fromNewPipeRows].
 */
object SubscriptionImport {

    data class ImportResult(
        val subscriptions: List<ImportedSubscription>,
        /** URLs without a channel id, to resolve over the network. */
        val unresolved: List<Pair<String, String>>,
        /** Entries for other services (SoundCloud, PeerTube…) that were skipped. */
        val skippedOtherServices: Int,
    )

    /** NewPipe's service id for YouTube in exports and its database. */
    const val NEWPIPE_YOUTUBE_SERVICE_ID = 0

    /** NewPipe "Export subscriptions": `{"app_version":…, "subscriptions":[{service_id,url,name}]}` */
    fun fromNewPipeJson(text: String): ImportResult {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw ImportFormatException("Not a NewPipe subscriptions export (not JSON)")
        val list = (root["subscriptions"] ?: throw ImportFormatException("Not a NewPipe subscriptions export (no \"subscriptions\")")).jsonArray
        val rows = list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val service = runCatching { o["service_id"]?.jsonPrimitive?.int }.getOrNull() ?: return@mapNotNull null
            val url = o["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Triple(service, url, o["name"]?.jsonPrimitive?.content.orEmpty())
        }
        return fromNewPipeRows(rows)
    }

    /** (service_id, url, name) rows, from the JSON export or the `subscriptions` table. */
    fun fromNewPipeRows(rows: List<Triple<Int, String, String>>): ImportResult {
        val yt = rows.filter { it.first == NEWPIPE_YOUTUBE_SERVICE_ID }
        return collect(yt.map { it.second to it.third }, skipped = rows.size - yt.size)
    }

    /**
     * Google Takeout `subscriptions.csv`: a header row (localised) then
     * `Channel Id,Channel Url,Channel Title`. Titles may be quoted and contain commas.
     */
    fun fromTakeoutCsv(text: String): ImportResult {
        val rows = text.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.isNotEmpty() }
            .map(::splitCsvLine)
            .filter { cols -> cols.size >= 2 && (YoutubeIds.isChannelId(cols[0]) || YoutubeIds.channelIdFrom(cols[1]) != null) }
            .map { cols -> (if (YoutubeIds.isChannelId(cols[0])) cols[0] else cols[1]) to cols.getOrElse(2) { "" } }
            .toList()
        if (rows.isEmpty() && text.isNotBlank()) throw ImportFormatException("No channels found; is this Takeout's subscriptions.csv?")
        return collect(rows, skipped = 0)
    }

    private fun collect(urlAndName: List<Pair<String, String>>, skipped: Int): ImportResult {
        val subs = LinkedHashMap<String, ImportedSubscription>()
        val unresolved = ArrayList<Pair<String, String>>()
        for ((url, name) in urlAndName) {
            val id = YoutubeIds.channelIdFrom(url)
            if (id == null) unresolved += url to name
            else subs.putIfAbsent(id, ImportedSubscription(SourceKey.YOUTUBE, id, name))
        }
        return ImportResult(subs.values.toList(), unresolved.distinctBy { it.first }, skipped)
    }

    internal fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString().trim()
        return out
    }
}
