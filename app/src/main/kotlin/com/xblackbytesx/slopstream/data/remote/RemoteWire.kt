package com.xblackbytesx.slopstream.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Playing on a TV (decision 28): the server's /api/v1/remote wire format
 * (`slopstream_core::remote`), pinned by its golden JSON like the sync format
 * (SyncContractTest). Top-level classes only (see AGENTS.md).
 */

/** What to play: the TV's player header, and the sender's history row when the TV plays it as a guest. */
@Serializable
data class RemoteItem(
    val source: String,
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String? = null,
    val thumbnailUrl: String? = null,
    val kind: String? = null,
)

@Serializable
enum class RemoteAction {
    @SerialName("play") PLAY,
    @SerialName("pause") PAUSE,
    @SerialName("resume") RESUME,
    @SerialName("seek") SEEK,
    @SerialName("stop") STOP,
}

@Serializable
enum class PlayState {
    @SerialName("starting") STARTING,
    @SerialName("buffering") BUFFERING,
    @SerialName("playing") PLAYING,
    @SerialName("paused") PAUSED,
    @SerialName("ended") ENDED,
    @SerialName("stopped") STOPPED,
    @SerialName("error") ERROR,
    ;

    /** The session is over: nothing to steer. */
    val final: Boolean get() = this == ENDED || this == STOPPED || this == ERROR
}

/** `POST /api/v1/remote/receive`: [since] null on the first call (nothing old is replayed). */
@Serializable
data class ReceiveRequest(val since: Long? = null, val services: List<String>)

@Serializable
data class ReceiveAnswer(val seq: Long, val commands: List<RemoteCommand> = emptyList())

/** [guest]: someone else's video; this TV keeps it out of its own library. */
@Serializable
data class RemoteCommand(
    val seq: Long,
    val session: String,
    val action: RemoteAction,
    val item: RemoteItem? = null,
    val positionMs: Long? = null,
    val guest: Boolean = false,
)

@Serializable
data class SendRequest(val item: RemoteItem, val startMs: Long)

@Serializable
data class SendAnswer(val session: String)

@Serializable
data class ControlRequest(val session: String, val action: RemoteAction, val positionMs: Long? = null)

/** `POST /api/v1/remote/state`; [durationMs] 0 for a live stream. */
@Serializable
data class StateReport(
    val session: String,
    val state: PlayState,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val error: String? = null,
)

/** As the TV last reported it, [at] in the server's time. */
@Serializable
data class NowPlaying(
    val session: String,
    val item: RemoteItem,
    val state: PlayState,
    val positionMs: Long,
    val durationMs: Long,
    val error: String? = null,
    val at: Long,
    val guest: Boolean = false,
)

/** A TV this person may play on, open now. [own]: paired to this person. */
@Serializable
data class Target(
    val id: String,
    val name: String,
    val own: Boolean,
    val services: List<String> = emptyList(),
    val nowPlaying: NowPlaying? = null,
)

@Serializable
data class Targets(val targets: List<Target> = emptyList())

@Serializable
data class RemoteStatus(val generation: Long, val online: Boolean, val nowPlaying: NowPlaying? = null)
