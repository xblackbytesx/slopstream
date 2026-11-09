package com.xblackbytesx.slopstream.youtube.bench

import com.xblackbytesx.slopstream.core.SourceBlockedException
import com.xblackbytesx.slopstream.core.SourceUnavailableException
import com.xblackbytesx.slopstream.core.VideoKind
import com.xblackbytesx.slopstream.core.playback.DecoderCaps
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.PlaybackPlan
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.core.playback.QualityCap
import com.xblackbytesx.slopstream.core.playback.Track
import com.xblackbytesx.slopstream.core.playback.VideoCodec
import com.xblackbytesx.slopstream.youtube.RssFeedFetcher
import com.xblackbytesx.slopstream.youtube.StreamRequests
import com.xblackbytesx.slopstream.youtube.YoutubeSource
import com.xblackbytesx.slopstream.youtube.innertube.Innertube
import com.xblackbytesx.slopstream.youtube.innertube.InnertubeResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Playback benchmark (`gradle :source-youtube:bench`): resolves a corpus of real videos and
 * checks that the chosen streams actually serve bytes, at the start and deep in the file, the
 * way the player asks for them. A resolve that parses but whose URLs 403 counts as a failure.
 *
 * Each [Resolver] runs over the same corpus, so extractors compare side by side. Results go to
 * `build/bench/` as a Markdown table plus a summary. Live network; never in CI.
 */
class PlaybackBench {
    /** Requests the resolver made for the current case: host + path, in order. */
    private val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val counted = client.newBuilder().addInterceptor { chain ->
        val u = chain.request().url
        requests += "${chain.request().method} ${u.host}${u.encodedPath}"
        chain.proceed(chain.request())
    }.build()

    /** A plan, and how it was found (shown in the note column). */
    fun interface Resolver {
        suspend fun resolve(videoId: String): Pair<PlaybackPlan, String>
    }

    private val resolvers: Map<String, Resolver> = linkedMapOf(
        "newpipe" to YoutubeSource(counted, Locale.UK).let { s -> Resolver { s.resolvePlayback(it, SHIELD, PlaybackPrefs(QualityCap.BEST)) to "" } },
        // Ours alone, no NewPipe fallback: its own score.
        "own" to InnertubeResolver(Innertube(counted), counted).let { r ->
            Resolver { id -> r.resolve(id, SHIELD, PlaybackPrefs(QualityCap.BEST)).let { it.plan to "${it.path}: ${it.steps.joinToString("; ")}" } }
        },
    )

    data class Case(val id: String, val label: String, val expect: Expect = Expect.PLAYS)
    enum class Expect { PLAYS, LIVE, UNAVAILABLE }

    enum class Outcome { OK, NOT_SERVED, UNAVAILABLE, BLOCKED, ERROR }

    data class Row(
        val case: Case,
        val outcome: Outcome,
        val resolveMs: Long,
        val firstByteMs: Long?,
        val plan: String,
        val clients: String,
        val probes: String,
        val note: String,
        val requests: List<String>,
    ) {
        /** Right means it behaved as expected: played, or refused a video that is gone. */
        val right: Boolean get() = when (case.expect) {
            Expect.PLAYS, Expect.LIVE -> outcome == Outcome.OK
            Expect.UNAVAILABLE -> outcome == Outcome.UNAVAILABLE
        }
    }

    @Test
    fun bench() = runBlocking {
        val corpus = corpus()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val out = File(System.getProperty("bench.out") ?: "build/bench").apply { mkdirs() }
        val only = System.getProperty("bench.resolvers")?.split(',')?.toSet()
        for ((name, resolver) in resolvers) {
            if (only != null && name !in only) continue
            val rows = corpus.map { case ->
                run(resolver, case).also {
                    println("[$name] ${it.outcome} ${case.id} ${case.label}: ${it.resolveMs} ms, ${it.plan} ${it.clients} ${it.probes} ${it.note}")
                    delay(SPACING_MS)
                }
            }
            val report = report(name, rows)
            File(out, "$name-$stamp.md").writeText(report)
            println(report)
        }
    }

    private suspend fun run(resolver: Resolver, case: Case): Row {
        requests.clear()
        val started = System.nanoTime()
        val (plan, how) = try {
            resolver.resolve(case.id)
        } catch (e: Exception) {
            val ms = (System.nanoTime() - started) / 1_000_000
            val outcome = when (e) {
                is SourceBlockedException -> Outcome.BLOCKED
                is SourceUnavailableException -> Outcome.UNAVAILABLE
                else -> Outcome.ERROR
            }
            val cause = generateSequence(e as Throwable) { it.cause }.last()
            val steps = (e as? InnertubeResolver.Failed)?.steps?.joinToString("; ")?.let { " [$it]" }.orEmpty()
            return Row(case, outcome, ms, null, "-", "-", "-", "${cause.javaClass.simpleName}: ${cause.message?.take(120)}$steps", requests.toList())
        }
        val resolveMs = (System.nanoTime() - started) / 1_000_000
        val made = requests.toList()
        val tracks: List<Pair<String, String>> = when (plan) {
            is PlaybackPlan.Adaptive -> listOf("v" to plan.video.url, "a" to plan.audio.url)
            is PlaybackPlan.Progressive -> listOf("p" to plan.url)
            is PlaybackPlan.Manifest -> listOf("m" to plan.url)
            else -> emptyList()
        }
        val described = when (plan) {
            is PlaybackPlan.Adaptive -> "adaptive ${plan.chosen.label}"
            is PlaybackPlan.Progressive -> "progressive"
            is PlaybackPlan.Manifest -> "manifest${if (plan.live) " live" else ""}"
            else -> plan::class.simpleName.orEmpty()
        }
        val clients = tracks.joinToString("/") { (_, url) -> Regex("[?&]c=([A-Z_]+)").find(url)?.groupValues?.get(1) ?: "?" }
        val probes = mutableListOf<String>()
        var served = true
        var firstByte: Long? = null
        for ((name, url) in tracks) {
            if (name == "m") {
                val code = get(url)
                probes += "m:$code"
                served = served && code in 200..299
                firstByte = firstByte ?: ((System.nanoTime() - started) / 1_000_000)
                continue
            }
            val size = contentLength(url)
            val positions = listOf(0L) + (size?.let { listOf(it * 8 / 10) } ?: emptyList())
            for (pos in positions) {
                val (code, bytes) = range(url, pos, PROBE_BYTES)
                if (pos == 0L && firstByte == null) firstByte = (System.nanoTime() - started) / 1_000_000
                val ok = code in 200..299 && bytes > 0
                probes += "$name@${if (pos == 0L) "0" else "80%"}:$code"
                served = served && ok
            }
        }
        return Row(
            case, if (served) Outcome.OK else Outcome.NOT_SERVED, resolveMs, firstByte,
            described, clients, probes.joinToString(" "), how, made,
        )
    }

    private fun shapedCall(url: String, position: Long, length: Long): Request {
        val s = StreamRequests.shape(url, position, length, requestNumber = 0)
        return Request.Builder().url(s.url).apply { s.headers.forEach { (k, v) -> header(k, v) } }.post(s.body.toRequestBody()).build()
    }

    private fun range(url: String, position: Long, length: Long): Pair<Int, Int> = runCatching {
        client.newCall(shapedCall(url, position, length)).execute().use { it.code to it.body.bytes().size }
    }.getOrElse { -1 to 0 }

    private fun contentLength(url: String): Long? =
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()

    private fun get(url: String): Int = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
    }.getOrElse { -1 }

    private fun report(name: String, rows: List<Row>): String = buildString {
        val right = rows.count { it.right }
        val plays = rows.filter { it.case.expect != Expect.UNAVAILABLE }
        val ok = plays.filter { it.outcome == Outcome.OK }
        fun pct(values: List<Long>, p: Int) = values.sorted().let { if (it.isEmpty()) "-" else it[(it.size - 1) * p / 100].toString() }
        appendLine("# Playback bench: $name")
        appendLine()
        appendLine("Behaved as expected: $right of ${rows.size}. Played: ${ok.size} of ${plays.size}.")
        appendLine("Resolve ms (played) p50 ${pct(ok.map { it.resolveMs }, 50)}, p90 ${pct(ok.map { it.resolveMs }, 90)}, max ${ok.maxOfOrNull { it.resolveMs } ?: "-"}.")
        appendLine("First media byte ms p50 ${pct(ok.mapNotNull { it.firstByteMs }, 50)}, p90 ${pct(ok.mapNotNull { it.firstByteMs }, 90)}.")
        appendLine("Outcomes: " + rows.groupingBy { it.outcome }.eachCount())
        appendLine("Requests per resolve (played): " + ok.map { it.requests.size }.sorted().joinToString(" "))
        appendLine()
        appendLine("Requests of the first played resolve (cold) and the last (warm):")
        ok.firstOrNull()?.requests?.forEach { appendLine("    cold $it") }
        ok.lastOrNull()?.requests?.forEach { appendLine("    warm $it") }
        appendLine()
        appendLine("| | video | case | outcome | resolve ms | plan | clients | requests | probes | note |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        rows.forEach { r ->
            appendLine("| ${if (r.right) "ok" else "**x**"} | ${r.case.id} | ${r.case.label} | ${r.outcome} | ${r.resolveMs} | ${r.plan} | ${r.clients} | ${r.requests.size} | ${r.probes} | ${r.note.replace("|", "/")} |")
        }
    }

    /**
     * Fixed cases that stress one thing each, plus the newest uploads of a spread of channels.
     * `-PbenchChannel=<UC id>` instead takes every upload in that channel's feeds, repeated
     * `-PbenchRounds` times (failures that come and go).
     */
    private fun corpus(): List<Case> {
        System.getProperty("bench.channel")?.let { channel ->
            val items = RssFeedFetcher(client).fetch(channel).items
            val rounds = System.getProperty("bench.rounds")?.toIntOrNull() ?: 1
            return (1..rounds).flatMap { round -> items.map { Case(it.remoteId, "r$round ${it.kind} ${it.title.take(40)}") } }
        }
        val fixed = listOf(
            Case("jNQXAC9IVRw", "2005, first upload"),
            Case("dQw4w9WgXcQ", "label music video"),
            Case("LXb3EKWsInQ", "4K60 HDR"),
            Case("aqz-KE-bpKQ", "Big Buck Bunny 4K60"),
            Case("4t4AQ15yj1M", "24/7 live (Lofi Girl, 2026-11)", Expect.LIVE),
            Case("gCNeDWCI0vo", "news live (Al Jazeera, 2026-11)", Expect.LIVE),
            Case("aaaaaaaaaaa", "does not exist", Expect.UNAVAILABLE),
        )
        val rss = RssFeedFetcher(client)
        val fresh = CHANNELS.flatMap { (channel, label) ->
            runCatching { rss.fetch(channel).items }.getOrDefault(emptyList())
                .let { items ->
                    listOfNotNull(
                        items.firstOrNull { it.kind == VideoKind.VIDEO }?.let { Case(it.remoteId, "$label newest") },
                        items.filter { it.kind == VideoKind.VIDEO }.getOrNull(5)?.let { Case(it.remoteId, "$label older") },
                        items.firstOrNull { it.kind == VideoKind.SHORT }?.let { Case(it.remoteId, "$label Short") },
                    )
                }
        }
        return (fixed + fresh).distinctBy { it.id }
    }

    private companion object {
        const val PROBE_BYTES = 65_536L
        const val SPACING_MS = 1_500L

        val CHANNELS = listOf(
            "UCXuqSBlHAE6Xw-yeJA0Tunw" to "LTT",
            "UCBJycsmduvYEL83R_U4JriQ" to "MKBHD",
            "UCHnyfMqiRRG1u-2MsSQLbXA" to "Veritasium",
            "UCsXVk37bltHxD1rDPwtNM8Q" to "Kurzgesagt",
            "UCYO_jab_esuFRV4b17AJtAw" to "3Blue1Brown",
            "UC-lHJZR3Gqxm24_Vd_AJ5Yw" to "PewDiePie",
            "UCX6OQ3DkcsbYNE6H8uQQuVA" to "MrBeast",
            "UCupvZG-5ko_eiXAupbDfxWw" to "CNN",
        )

        private val ladder = listOf(480, 720, 1080, 1440, 2160).associateWith { 60 }
        val SHIELD = DeviceProfile(
            decoders = listOf(
                DecoderCaps(VideoCodec.VP9, true, ladder, hdr = true),
                DecoderCaps(VideoCodec.H264, true, ladder),
            ),
            hdrDisplay = false,
            displayLines = 2160,
            throughputKbps = null,
            matchFrameRate = true,
        )
    }
}
