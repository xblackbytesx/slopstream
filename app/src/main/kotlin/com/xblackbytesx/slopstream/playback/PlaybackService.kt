package com.xblackbytesx.slopstream.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
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

    /** The running service, while there is one; set and cleared by the service itself. */
    @Volatile
    internal var service: PlaybackService? = null

    private var listener: Player.Listener? = null

    /** Publishes [player]; call on the main thread while the app is in front. */
    fun start(context: Context, player: Player) {
        val app = context.applicationContext
        if (session?.player !== player) {
            stop(app)
            val open = PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session = MediaSession.Builder(app, player).setSessionActivity(open).build()
            // Every press of play, wherever it comes from (the mini player, the player screen, the
            // notification), makes sure the service is there to hold the play in the foreground.
            listener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady) ensureService(app)
                }
            }.also(player::addListener)
        }
        ensureService(app)
    }

    /**
     * The service is what keeps the app running with the screen off: Media3 puts it in the
     * foreground while the session plays. Android stops it while a paused app sits in the
     * background, and the session lives on without it, so a later play must start it again.
     * Missing that was the "stops a few seconds after the screen goes off" bug (2026-11-09).
     */
    internal fun ensureService(context: Context) {
        val s = session ?: return
        service?.let { running ->
            if (!running.isSessionAdded(s)) running.addSession(s)
            return
        }
        try {
            context.startService(Intent(context, PlaybackService::class.java))
        } catch (e: IllegalStateException) {
            // In the background (a headset's play button with the app idle): Android refuses a
            // service start here. Playing in the app, or from its notification, always works.
            Log.w("BackgroundAudio", "Could not start the playback service in the background", e)
        }
    }

    /** Ends the session if it is [player]'s (any session when null); the notification goes. */
    fun stop(context: Context, player: Player? = null) {
        val s = session ?: return
        if (player != null && s.player !== player) return
        session = null
        listener?.let(s.player::removeListener)
        listener = null
        context.stopService(Intent(context, PlaybackService::class.java))
        s.release()
    }
}

class PlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        BackgroundAudio.service = this
        BackgroundAudio.session?.let(::addSession)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BackgroundAudio.session?.let { if (!isSessionAdded(it)) addSession(it) }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = BackgroundAudio.session

    /** Swiped away from recents while paused: nothing left to keep running. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = BackgroundAudio.session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        if (BackgroundAudio.service === this) BackgroundAudio.service = null
        super.onDestroy()
    }
}
