package com.xblackbytesx.slopstream.floatplane

import com.xblackbytesx.slopstream.core.SourceException
import com.xblackbytesx.slopstream.core.playback.DecoderCaps
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.core.playback.QualityCap
import com.xblackbytesx.slopstream.core.playback.StreamSelector
import com.xblackbytesx.slopstream.core.playback.VideoCodec
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * The Floatplane spike, live against your own account (`make smoke-floatplane`). Read-only:
 * it never writes progress, likes or anything else. It prints a report to paste back.
 *
 * Credentials, first match wins (never printed):
 *  - `FP_CLIENT_ID`: Slopstream's registered client id. Runs the device login (open the printed
 *    link on any device) and caches the tokens in `.secrets/floatplane-tokens.json`.
 *  - `FP_BEARER`: an access token copied from your own signed-in browser session.
 *  - `FP_SESSION`: the `sails.sid` cookie value from your own signed-in browser session.
 *  - `SP_SESSION`: the same for Sauce+ (www.sauceplus.com), which runs Floatplane's platform
 *    with its own accounts; there the cookie is `__Host-sp-sess`. Runs the same report.
 *
 * The delivery endpoint is rate limited, so the report makes three delivery calls, 31 s apart.
 */
class FloatplaneSmokeTest {
    private val client = OkHttpClient()
    private val secrets = File(System.getProperty("fp.secrets") ?: ".secrets")
    private val tokenFile = File(secrets, "floatplane-tokens.json")

    private val ladder = listOf(480, 720, 1080, 1440, 2160).associateWith { 60 }
    private val shield = DeviceProfile(
        decoders = listOf(
            DecoderCaps(VideoCodec.VP9, true, ladder, hdr = true),
            DecoderCaps(VideoCodec.HEVC, true, ladder, hdr = true),
            DecoderCaps(VideoCodec.H264, true, ladder),
        ),
        hdrDisplay = false, displayLines = 2160, throughputKbps = null, matchFrameRate = true,
    )

    private fun redact(s: String) = s.replace(Regex("(token|Signature|Policy|Key-Pair-Id|hdnts|hdnea)=[^&\\s\"]+"), "$1=<redacted>")
    private fun section(title: String) = println("\n=== $title")

    /** Sauce+ when `SP_SESSION` is set: same platform, its own site and accounts. */
    private val sauce = !System.getenv("SP_SESSION").isNullOrBlank()
    private val baseUrl = if (sauce) "https://www.sauceplus.com" else Floatplane.BASE_URL

    private suspend fun authorizer(): Pair<String, FloatplaneAuthorizer>? {
        System.getenv("SP_SESSION")?.takeIf { it.isNotBlank() }?.let { c ->
            // Sauce+ names Floatplane's sails.sid "__Host-sp-sess" (seen in the user's browser 2026-10-23).
            return "SP_SESSION cookie (Sauce+)" to FloatplaneAuthorizer { it.header("Cookie", "__Host-sp-sess=${c.removePrefix("__Host-sp-sess=").trim()}") }
        }
        val clientId = System.getenv("FP_CLIENT_ID")?.takeIf { it.isNotBlank() }
        if (clientId != null) {
            val auth = FloatplaneAuth(client, clientId)
            var tokens = tokenFile.takeIf { it.exists() }?.let { runCatching { FloatplaneJsonFormat.decodeFromString(FloatplaneAuth.Tokens.serializer(), it.readText()) }.getOrNull() }
            if (tokens == null) {
                val code = auth.startDeviceLogin()
                println("\n>>> Open ${code.verificationUriComplete ?: code.verificationUri} and confirm code ${code.userCode} (expires ${code.expiresAt})\n")
                tokens = auth.awaitDeviceLogin(code)
                save(tokens)
            }
            return "device login ($clientId)" to TokenAuthorizer(auth, { tokens }, { tokens = it; save(it) })
        }
        System.getenv("FP_BEARER")?.takeIf { it.isNotBlank() }?.let { t ->
            return "FP_BEARER" to FloatplaneAuthorizer { it.header("Authorization", "Bearer ${t.removePrefix("Bearer ").trim()}") }
        }
        System.getenv("FP_SESSION")?.takeIf { it.isNotBlank() }?.let { c ->
            return "FP_SESSION cookie" to FloatplaneAuthorizer { it.header("Cookie", "sails.sid=${c.removePrefix("sails.sid=").trim()}") }
        }
        return null
    }

    private fun save(tokens: FloatplaneAuth.Tokens) {
        secrets.mkdirs()
        tokenFile.writeText(FloatplaneJsonFormat.encodeToString(FloatplaneAuth.Tokens.serializer(), tokens))
    }

    @Test
    fun report() = runTest(timeout = 15.minutes) {
        val (how, authz) = authorizer() ?: run {
            println("Floatplane smoke skipped: set FP_CLIENT_ID, FP_BEARER, FP_SESSION or SP_SESSION (see FloatplaneSmokeTest).")
            assumeTrue(false)
            return@runTest
        }
        val api = FloatplaneApi(client, authz, baseUrl)
        val source = FloatplaneSource(api)
        val findings = mutableListOf<String>()
        findings += "site=$baseUrl"

        section("Auth via $how")
        val self = try {
            api.self()
        } catch (e: SourceException) {
            // A Cloudflare challenge page instead of JSON shows up here as "unexpected".
            findings += "self failed: ${e.message?.take(160)}"
            println("FINDINGS\n" + findings.joinToString("\n") { "  - $it" })
            throw e
        }
        println("signed in as ${self.username}")

        section("Subscriptions")
        val subs = api.subscriptions()
        subs.forEach { println("  creator=${it.creator} plan='${it.plan.title}' ends=${it.endDate} cancelled=${it.paymentCancelled}") }
        findings += "subscriptions=${subs.size} distinct creators=${subs.map { it.creator }.distinct().size} plans=${subs.map { it.plan.title }.distinct()}"

        // Sauce+ sells one subscription for every creator ("global"): what does the list say?
        section("Creator list (search=\"\")")
        val listed = runCatching { api.creators() }
        listed.onSuccess { list -> list.forEach { println("  ${it.title} (${it.id}, /${it.urlname}) liveStream=${it.liveStream != null}") } }
            .onFailure { println("  failed: ${it.message}") }
        findings += "creator list: " + (listed.getOrNull()?.let { "${it.size} creators" } ?: "failed (${listed.exceptionOrNull()?.message?.take(80)})")

        val creatorIds = subs.map { it.creator }.distinct().ifEmpty { listed.getOrNull().orEmpty().map { it.id }.take(3) }
        assumeTrue("no subscriptions and no creators", creatorIds.isNotEmpty())

        section("Creators and sub-channels")
        val creators = creatorIds.map { api.creator(it) }
        val channels = api.channels(creators.map { it.id })
        for (c in creators) {
            println("  ${c.title} (${c.id}, /${c.urlname}) icon=${c.icon != null} liveStream=${c.liveStream?.let { "${it.id} '${it.title}' channel=${it.channel} offline=${it.offline?.toString()?.take(120)}" }}")
            channels.filter { it.creator == c.id }.sortedBy { it.order }.forEach { println("    channel ${it.id} '${it.title}' /${it.urlname} order=${it.order}") }
        }
        findings += "sub-channels=" + creators.joinToString { c -> "${c.title}:${channels.count { it.creator == c.id }}" }

        section("Posts (latest 20 per creator, video posts only)")
        var newestVideoPost: String? = null
        val allPostIds = mutableListOf<String>()
        var newestMulti: String? = null
        for (c in creators) {
            val posts = api.posts(c.id)
            allPostIds += posts.map { it.id }
            val chanShape = posts.groupBy { it.channel?.let { j -> j::class.simpleName } ?: "missing" }.mapValues { it.value.size }
            println("  ${c.title}: ${posts.size} posts, channel field shapes=$chanShape")
            posts.take(8).forEach { p ->
                val m = p.metadata
                println("    ${p.releaseDate} ${p.id} [${FloatplaneMapping.channelIdOf(p)}] v=${m.videoCount} dur=${m.videoDuration} a=${m.hasAudio} pic=${m.hasPicture} access=${p.isAccessible} thumb=${p.thumbnail?.let { "${it.width}x${it.height}+${it.childImages?.size}" }} '${p.title.take(60)}'")
            }
            findings += "${c.title}: posts=${posts.size} multiVideo=${posts.count { it.metadata.videoCount > 1 }} locked=${posts.count { !it.isAccessible }} noThumb=${posts.count { it.thumbnail == null }} channelShapes=$chanShape"
            if (newestVideoPost == null) newestVideoPost = posts.firstOrNull { it.isAccessible && it.metadata.hasVideo }?.id
            if (newestMulti == null) newestMulti = posts.firstOrNull { it.isAccessible && it.metadata.videoCount > 1 }?.id
            val feed = source.fetchChannelFeed(c.id)
            println("  mapped feed: ${feed.items.size} items; first: ${feed.items.take(3).map { "${it.channelName} | ${it.title.take(40)} | ${it.durationSec}s" }}")
        }

        section("Post detail")
        val postId = newestVideoPost ?: error("no accessible video post found")
        val post = api.post(postId)
        println("  $postId '${post.title}' order=${post.attachmentOrder}")
        post.videoAttachments.forEach { println("    video ${it.id} '${it.title.take(50)}' ${it.duration}s processing=${it.isProcessing} access=${it.isAccessible}") }
        newestMulti?.let { id ->
            val multi = api.post(id)
            println("  multi-video $id order=${multi.attachmentOrder}")
            multi.videoAttachments.forEach { println("    video ${it.id} '${it.title.take(50)}' ${it.duration}s processing=${it.isProcessing}") }
        }
        val attachment = FloatplaneMapping.firstPlayable(post) ?: error("post $postId has no playable attachment")

        comments(api, source, creators.flatMap { c -> api.posts(c.id, limit = 5).filter { it.isAccessible } }.map { it.id }, findings)

        section("Delivery onDemand (default outputKind)")
        val delivery = api.delivery("onDemand", attachment.id)
        printDelivery(delivery)
        val variants = FloatplaneMapping.variants(delivery)
        val best = StreamSelector.selectVideo(variants.map { it.candidate }, shield, PlaybackPrefs())
        val capped = StreamSelector.selectVideo(variants.map { it.candidate }, shield, PlaybackPrefs(QualityCap.P1080))
        println("  Shield pick: ${best?.label} (${best?.width}x${best?.height}); 1080 cap: ${capped?.label} (${capped?.width}x${capped?.height})")
        findings += "onDemand variants=" + variants.joinToString { "${it.label}/${it.candidate.codecs}/${it.candidate.width}x${it.candidate.height}@${it.candidate.fps}" }
        val chosen = variants.first { it.candidate == best }
        probeHls(chosen.url, authz, findings)

        Thread.sleep(31_000)
        section("Delivery onDemand, outputKind=hls.fmp4 (other codecs?)")
        runCatching { api.delivery("onDemand", attachment.id, outputKind = "hls.fmp4") }
            .onSuccess { d -> printDelivery(d); findings += "fmp4 variants=" + FloatplaneMapping.variants(d).joinToString { "${it.label}/${it.candidate.codecs}" } }
            .onFailure { println("  failed: $it"); findings += "fmp4: $it" }

        creators.firstOrNull { it.liveStream != null }?.let { c ->
            Thread.sleep(31_000)
            section("Delivery live (${c.title}, ${Instant.now()})")
            runCatching { api.delivery("live", c.liveStream!!.id) }
                .onSuccess { d ->
                    printDelivery(d)
                    findings += "live delivery: variants=${d.groups.sumOf { it.variants.size }} url=${FloatplaneMapping.liveUrl(d) != null}"
                    FloatplaneMapping.liveUrl(d)?.let { probeLive(it, findings) }
                }
                .onFailure { println("  failed: $it"); findings += "live delivery while (probably) offline: $it" }
        }

        section("Progress (read only)")
        runCatching { api.progress(allPostIds) }
            .onSuccess { p ->
                println("  ${p.size} entries for ${allPostIds.size} posts: $p")
                findings += "progress: ${p.size} entries for ${allPostIds.size} posts, ${p.count { it.progress > 0 }} with progress > 0, values=${p.map { it.progress }.distinct().sorted()}"
            }
            .onFailure { println("  failed: $it"); findings += "progress: $it" }

        section("FINDINGS (paste this back)")
        findings.forEach { println("  - ${redact(it)}") }
    }

    /**
     * The spec's comment paging, checked: does `fetchAfter` (last comment id) move on, do the
     * inline replies come oldest first, and does `rid` (last inline reply) continue after them?
     * Prints counts, ids and dates only, plus whether texts look like HTML; no comment text.
     */
    private suspend fun comments(api: FloatplaneApi, source: FloatplaneSource, postIds: List<String>, findings: MutableList<String>) {
        section("Comments")
        val counted = postIds.map { id -> id to (runCatching { api.comments(id, 20) }.getOrElse { e -> println("  $id failed: $e"); findings += "comments $id: $e"; return }) }
        counted.forEach { (id, page) -> println("  post $id: ${page.size} comments, ${page.sumOf { it.totalReplies }} replies") }
        val (postId, first) = counted.maxByOrNull { (_, page) -> page.size + page.sumOf { it.totalReplies } } ?: return
        val owner = FloatplaneMapping.ownerOf(api.post(postId))
        val html = first.count { Regex("<[a-z/]|&[a-z#0-9]+;").containsMatchIn(it.text) }
        println("  post $postId owner=$owner: ${first.size} comments, pinned=${first.count { it.pinDate != null }} byOwner=${first.count { it.user.id == owner }} htmlish=$html")
        first.take(6).forEach { println("    ${it.id} ${it.postDate} likes=${it.interactionCounts?.like}/${it.likes} replies=${it.replies?.size}/${it.totalReplies}") }
        findings += "comments on $postId: first page ${first.size}, owner known=${owner != null}, byOwner=${first.count { it.user.id == owner }}, pinned=${first.count { it.pinDate != null }}, htmlish texts=$html"
        findings += "comment order: " + first.mapNotNull { it.postDate }.let { d -> if (d == d.sorted()) "oldest first" else if (d == d.sortedDescending()) "newest first" else "mixed (ranked?)" }

        if (first.size == 20) {
            val second = api.comments(postId, 20, fetchAfter = first.last().id)
            val overlap = second.count { c -> first.any { it.id == c.id } }
            println("  page 2 after ${first.last().id}: ${second.size} comments, $overlap repeated")
            findings += "comments page 2: ${second.size}, repeated from page 1: $overlap"
        }

        val threaded = first.filter { it.totalReplies > it.replies.orEmpty().size && it.replies.orEmpty().isNotEmpty() }.maxByOrNull { it.totalReplies }
            ?: first.filter { it.replies.orEmpty().isNotEmpty() }.maxByOrNull { it.totalReplies }
        if (threaded == null) {
            findings += "replies: no comment with inline replies on that page"
        } else {
            val inline = threaded.replies.orEmpty()
            val dates = inline.mapNotNull { it.postDate }
            val more = api.replies(threaded.id, postId, 20, inline.last().id)
            val overlap = more.count { r -> inline.any { it.id == r.id } }
            val after = more.mapNotNull { it.postDate }.all { d -> dates.all { it <= d } }
            println("  ${threaded.id}: ${inline.size} inline of ${threaded.totalReplies}, dates=$dates; after rid ${more.size}, $overlap repeated, all later=$after")
            findings += "replies: inline ${inline.size} of ${threaded.totalReplies} (${if (dates == dates.sorted()) "oldest first" else "NOT oldest first"}); after rid: ${more.size}, repeated $overlap, all later=$after"
        }

        // The same through the source, as the app pages it.
        val mapped = source.comments(postId)
        val withReplies = mapped.comments.firstOrNull { it.replies != null }
        var replies = 0
        var cursor = withReplies?.replies
        var pages = 0
        while (cursor != null && pages < 10) {
            val p = source.comments(postId, cursor)
            replies += p.comments.size
            cursor = p.next
            pages++
        }
        findings += "source: ${mapped.comments.size} comments, more=${mapped.next != null}; replies of ${withReplies?.id}: $replies of ${withReplies?.replyCount} in $pages pages"
    }

    private fun printDelivery(d: DeliveryDto) {
        d.groups.forEachIndexed { i, g ->
            println("  group $i origins=${g.origins.map { it.url }}")
            g.variants.forEach { v ->
                val vm = v.meta?.video
                println("    ${v.name} '${v.label}' enabled=${v.enabled} hidden=${v.hidden} mime=${v.mimeType} video=${vm?.let { "${it.codec} ${it.width}x${it.height}@${it.fps} hdr=${it.isHdr} ${it.bitrate?.average}bps" }} audio=${v.meta?.audio?.codec} ownOrigins=${v.origins?.size} url=${redact(v.url).take(90)}")
            }
        }
    }

    /**
     * Can the player fetch the playlist, the AES-128 key and a segment with only the URL tokens,
     * and does the key decrypt the segment into MPEG-TS? Prints sizes and status codes only,
     * never key bytes.
     */
    private suspend fun probeHls(url: String, authz: FloatplaneAuthorizer, findings: MutableList<String>) {
        section("HLS fetch without credentials")
        val (code, body) = client.newCall(Request.Builder().url(url).header("User-Agent", Floatplane.USER_AGENT).build()).execute().use { it.code to it.body.string() }
        println("  playlist HTTP $code, ${body.length} chars")
        body.lineSequence().take(6).forEach { println("    ${redact(it)}") }
        val lines = body.lineSequence().map { it.trim() }.toList()
        val first = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        if (code !in 200..299 || first == null) {
            findings += "HLS playlist without credentials: HTTP $code"
            return
        }
        val segUrl = url.toHttpUrl().resolve(first) ?: throw SourceException("bad segment uri")
        val segment = client.newCall(Request.Builder().url(segUrl).header("Range", "bytes=0-65535").header("User-Agent", Floatplane.USER_AGENT).build())
            .execute().use { r -> r.code to r.body.bytes() }
        println("  first segment: HTTP ${segment.first}, ${segment.second.size} bytes")
        findings += "HLS without credentials: playlist $code, first segment ${segment.first}"

        val keyLine = lines.firstOrNull { it.startsWith("#EXT-X-KEY") }
        if (keyLine == null) {
            findings += "HLS: not encrypted"
            return
        }
        val method = Regex("METHOD=([A-Z0-9-]+)").find(keyLine)?.groupValues?.get(1)
        val keyUri = Regex("URI=\"([^\"]+)\"").find(keyLine)?.groupValues?.get(1)?.let { url.toHttpUrl().resolve(it) }
        val ivHex = Regex("IV=0[xX]([0-9a-fA-F]+)").find(keyLine)?.groupValues?.get(1)
        val mediaSequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }?.substringAfter(':')?.toLongOrNull() ?: 0L
        println("  key: method=$method host=${keyUri?.host} path=${keyUri?.encodedPath} explicitIV=${ivHex != null} mediaSequence=$mediaSequence")
        if (keyUri == null) return

        fun keyRequest() = Request.Builder().url(keyUri).header("User-Agent", Floatplane.USER_AGENT)
        val bare = client.newCall(keyRequest().build()).execute().use { r -> Triple(r.code, r.header("Content-Type"), r.body.bytes()) }
        println("  key without credentials: HTTP ${bare.first} ${bare.second} ${bare.third.size} bytes")
        val signedIn = client.newCall(keyRequest().also { authz.authorize(it) }.build()).execute().use { r -> Triple(r.code, r.header("Content-Type"), r.body.bytes()) }
        println("  key with credentials: HTTP ${signedIn.first} ${signedIn.second} ${signedIn.third.size} bytes")
        findings += "AES key: without credentials HTTP ${bare.first}/${bare.third.size}B, with credentials HTTP ${signedIn.first}/${signedIn.third.size}B"
        if (bare.first == 403) findings += "AES key refusal reason: ${errorNames(bare.third.decodeToString())}"

        // What a browser on this site sends: its Origin and Referer, no credentials (the Sauce+
        // player uses withCredentials=false). And the same path on this site with the session.
        val site = baseUrl.toHttpUrl()
        val origin = "${site.scheme}://${site.host}"
        val fromSite = client.newCall(keyRequest().header("Origin", origin).header("Referer", "$origin/").build()).execute()
            .use { r -> Triple(r.code, r.header("Content-Type"), r.body.bytes()) }
        println("  key with Origin $origin: HTTP ${fromSite.first} ${fromSite.second} ${fromSite.third.size} bytes")
        findings += "AES key with Origin/Referer of the site: HTTP ${fromSite.first}/${fromSite.third.size}B"
        val sameSite = if (keyUri.host != site.host) {
            val onSite = keyUri.newBuilder().host(site.host).build()
            client.newCall(Request.Builder().url(onSite).header("User-Agent", Floatplane.USER_AGENT).also { authz.authorize(it) }.build()).execute()
                .use { r -> Triple(r.code, r.header("Content-Type"), r.body.bytes()) }
                .also {
                    println("  key path on ${site.host} with credentials: HTTP ${it.first} ${it.second} ${it.third.size} bytes")
                    findings += "AES key path on ${site.host} with credentials: HTTP ${it.first}/${it.third.size}B"
                }
        } else {
            null
        }

        val key = listOfNotNull(bare, signedIn, fromSite, sameSite).firstOrNull { it.first in 200..299 && it.third.size == 16 }?.third
        if (key == null || segment.first !in 200..299) {
            findings += "AES: no usable 16-byte key, could not test decryption"
            return
        }
        // HLS: without an IV attribute the IV is the segment's media sequence number, big endian.
        val iv = ivHex?.padStart(32, '0')?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            ?: java.nio.ByteBuffer.allocate(16).putLong(8, mediaSequence).array()
        val data = segment.second.copyOf(segment.second.size / 16 * 16)
        val clear = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
            init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
            doFinal(data)
        }
        val ts = clear.size > 376 && clear[0] == 0x47.toByte() && clear[188] == 0x47.toByte() && clear[376] == 0x47.toByte()
        println("  decrypted ${clear.size} bytes: MPEG-TS sync bytes ${if (ts) "present" else "MISSING"}")
        findings += "AES decryption with that key: ${if (ts) "valid MPEG-TS" else "NOT TS (wrong key/IV?)"}"
    }

    /** Offline or live: does the livestream playlist itself answer? */
    private fun probeLive(url: String, findings: MutableList<String>) {
        val (code, body) = client.newCall(Request.Builder().url(url).header("User-Agent", Floatplane.USER_AGENT).build()).execute().use { it.code to it.body.string() }
        val variants = body.lineSequence().count { it.startsWith("#EXT-X-STREAM-INF") }
        println("  live playlist: HTTP $code, ${body.length} chars, $variants variants; ${redact(body.take(160)).replace("\n", " | ")}")
        findings += "live playlist at ${Instant.now()}: HTTP $code, $variants variants"
    }
}
