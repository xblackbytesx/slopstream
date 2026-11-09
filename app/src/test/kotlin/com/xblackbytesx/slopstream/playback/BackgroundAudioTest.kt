package com.xblackbytesx.slopstream.playback

import android.app.Application
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The background play must always have its service: the bug of 2026-11-09 was a session that
 * outlived its service (Android stops it while a paused app sits in the background), so a later
 * play on the same player never started it again and the app froze with the screen off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackgroundAudioTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var player: ExoPlayer

    @Before fun setUp() {
        player = ExoPlayer.Builder(app).build()
        drainStarts()
    }

    @After fun tearDown() {
        BackgroundAudio.stop(app)
        player.release()
    }

    private fun drainStarts() {
        while (true) shadowOf(app).nextStartedService ?: break
    }

    private fun serviceStarted(): Boolean {
        val intent = shadowOf(app).nextStartedService ?: return false
        assertEquals(PlaybackService::class.java.name, intent.component?.className)
        drainStarts()
        return true
    }

    @Test
    fun aPlayAfterAndroidStoppedTheServiceStartsItAgain() {
        BackgroundAudio.start(app, player)
        assertTrue("the first play starts the service", serviceStarted())
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        assertSame(service.get(), BackgroundAudio.service)
        assertTrue(service.get().isSessionAdded(BackgroundAudio.session!!))

        // Paused in the background for a while: Android stops the service, the session stays.
        player.playWhenReady = false
        service.destroy()
        assertNull(BackgroundAudio.service)

        // Play again from the mini player: the same player, no new load.
        player.playWhenReady = true
        assertTrue("the service comes back", serviceStarted())
    }

    @Test
    fun loadingAgainOnTheSamePlayerChecksTheServiceToo() {
        BackgroundAudio.start(app, player)
        drainStarts()
        BackgroundAudio.start(app, player)
        assertTrue(serviceStarted())
    }

    @Test
    fun aRunningServiceIsNotStartedAgainButTakesTheSession() {
        BackgroundAudio.start(app, player)
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        drainStarts()
        player.playWhenReady = false
        player.playWhenReady = true
        assertTrue(!serviceStarted())
        assertTrue(service.get().isSessionAdded(BackgroundAudio.session!!))
        service.destroy()
    }

    @Test
    fun afterStopAPlayNoLongerStartsIt() {
        BackgroundAudio.start(app, player)
        BackgroundAudio.stop(app, player)
        drainStarts()
        player.playWhenReady = true
        assertTrue(!serviceStarted())
        assertNull(BackgroundAudio.session)
    }
}
