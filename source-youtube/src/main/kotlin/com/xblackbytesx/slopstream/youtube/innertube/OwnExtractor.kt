package com.xblackbytesx.slopstream.youtube.innertube

/**
 * The experimental extractor as the app switches it (decision 30): [enabled] is read on every
 * play, [onReport] hears how each one went, ours or the NewPipe fallback.
 */
class OwnExtractor(
    val resolver: InnertubeResolver,
    val enabled: () -> Boolean,
    val onReport: (ResolveReport) -> Unit,
)

/** One play (or quality switch) with the experimental extractor on. */
data class ResolveReport(
    val videoId: String,
    /** Epoch milliseconds. */
    val at: Long,
    val kind: String,
    val ownMs: Long,
    /** How ours found it (`dash`, `dash-new-visitor`, `hls`, `hls-live`); null when it failed. */
    val ownPath: String?,
    val ownError: String?,
    val ownSteps: List<String>,
    /** Set when NewPipe was asked after ours failed. */
    val newpipeMs: Long? = null,
    val newpipeError: String? = null,
    /** From whichever plan played; empty when nothing did. */
    val title: String = "",
) {
    val ownOk: Boolean get() = ownPath != null
    val played: Boolean get() = ownOk || (newpipeMs != null && newpipeError == null)
}
