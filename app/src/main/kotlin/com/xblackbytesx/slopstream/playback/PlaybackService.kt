package com.xblackbytesx.slopstream.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.xblackbytesx.slopstream.MainActivity

/**
 * Keeps an audio-only download playing with the screen off or the app in the background, like
 * a podcast player (decision 25): a [MediaSession] on the player screen's own ExoPlayer, which
 * [PlaybackService] publishes. Media3 then shows the media notification and lock-screen
 * controls and holds the service in the foreground while it plays. Video plays never use it:
 * they go to picture-in-picture instead.
 */
object BackgroundAudio {
    @Volatile
    var session: MediaSession? = null
        private set

    /** Publishes [player]; call on the main thread while the app is in front. */
    fun start(context: Context, player: Player) {
        if (session?.player === player) return
        stop(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(context, player).setSessionActivity(open).build()
        context.startService(Intent(context, PlaybackService::class.java))
    }

    /** Ends the session if it is [player]'s (any session when null); the notification goes. */
    fun stop(context: Context, player: Player? = null) {
        val s = session ?: return
        if (player != null && s.player !== player) return
        session = null
        context.stopService(Intent(context, PlaybackService::class.java))
        s.release()
    }
}

class PlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        BackgroundAudio.session?.let(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = BackgroundAudio.session

    /** Swiped away from recents while paused: nothing left to keep running. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = BackgroundAudio.session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }
}
