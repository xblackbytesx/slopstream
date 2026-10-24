package com.xblackbytesx.slopstream.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.core.playback.DecoderCaps
import com.xblackbytesx.slopstream.core.playback.DeviceProfile
import com.xblackbytesx.slopstream.core.playback.PlaybackPrefs
import com.xblackbytesx.slopstream.core.playback.QualityCap
import com.xblackbytesx.slopstream.core.playback.VideoCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

data class AppSettings(
    val cap: QualityCap,
    val allowHdr: Boolean,
    /** Livestreams and premieres in the main feed (Shorts never are). */
    val showLiveInFeed: Boolean,
    /** Background refresh interval; 0 = only when the app is opened. */
    val refreshHours: Int,
    val profile: DeviceProfile?,
    /** The cap the last device check suggested, shown next to the user's choice. */
    val recommendedCap: QualityCap?,
    /** Switch the display refresh rate to the video's (TV). Defaults to the device check. */
    val matchFrameRate: Boolean,
    /** The service the list screens showed last; [Services.current] checks it is still there. */
    val service: SourceKey? = null,
    /** A Comments button in the player and the video menu (decision 20). Off by default. */
    val showComments: Boolean = false,
) {
    val prefs: PlaybackPrefs get() = PlaybackPrefs(cap = cap, allowHdr = allowHdr)
    val effectiveProfile: DeviceProfile get() = profile ?: DeviceProfile.CONSERVATIVE
}

@Singleton
class Settings @Inject constructor(@ApplicationContext private val context: Context) {
    private val store = context.dataStore
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val cap = stringPreferencesKey("quality_cap")
        val allowHdr = booleanPreferencesKey("allow_hdr")
        val showLive = booleanPreferencesKey("show_live_in_feed")
        val refreshHours = intPreferencesKey("refresh_hours")
        val profile = stringPreferencesKey("device_profile")
        val recommendedCap = stringPreferencesKey("recommended_cap")
        val lastRefreshAt = longPreferencesKey("last_refresh_at")
        val matchFrameRate = booleanPreferencesKey("match_frame_rate")
        val service = stringPreferencesKey("service")
        val showComments = booleanPreferencesKey("show_comments")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        val profile = p[Keys.profile]?.let { runCatching { json.decodeFromString<ProfileDto>(it).toModel() }.getOrNull() }
        AppSettings(
            cap = p[Keys.cap]?.let { runCatching { QualityCap.valueOf(it) }.getOrNull() } ?: QualityCap.BEST,
            allowHdr = p[Keys.allowHdr] ?: true,
            showLiveInFeed = p[Keys.showLive] ?: true,
            refreshHours = p[Keys.refreshHours] ?: 3,
            profile = profile,
            recommendedCap = p[Keys.recommendedCap]?.let { runCatching { QualityCap.valueOf(it) }.getOrNull() },
            matchFrameRate = p[Keys.matchFrameRate] ?: profile?.matchFrameRate ?: false,
            service = p[Keys.service]?.let { id -> SourceKey.entries.firstOrNull { it.id == id } },
            showComments = p[Keys.showComments] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /** A random id per install, written into synced rows. Created on first use. */
    suspend fun deviceId(): String {
        store.data.first()[Keys.deviceId]?.let { return it }
        val id = UUID.randomUUID().toString()
        store.edit { if (it[Keys.deviceId] == null) it[Keys.deviceId] = id }
        return store.data.first()[Keys.deviceId] ?: id
    }

    suspend fun setCap(cap: QualityCap) = store.edit { it[Keys.cap] = cap.name }
    suspend fun setAllowHdr(on: Boolean) = store.edit { it[Keys.allowHdr] = on }
    suspend fun setShowLive(on: Boolean) = store.edit { it[Keys.showLive] = on }
    suspend fun setRefreshHours(hours: Int) = store.edit { it[Keys.refreshHours] = hours }
    suspend fun setMatchFrameRate(on: Boolean) = store.edit { it[Keys.matchFrameRate] = on }
    suspend fun setService(source: SourceKey) = store.edit { it[Keys.service] = source.id }
    suspend fun setShowComments(on: Boolean) = store.edit { it[Keys.showComments] = on }

    /** Stores a device check result; the cap follows the recommendation only on the first run. */
    suspend fun saveDeviceCheck(profile: DeviceProfile, recommended: QualityCap) = store.edit {
        val first = it[Keys.profile] == null
        it[Keys.profile] = json.encodeToString(ProfileDto.serializer(), ProfileDto.from(profile))
        it[Keys.recommendedCap] = recommended.name
        if (first) it[Keys.cap] = recommended.name
    }

    suspend fun lastRefreshAt(): Long = store.data.first()[Keys.lastRefreshAt] ?: 0L
    suspend fun setLastRefreshAt(at: Long) = store.edit { it[Keys.lastRefreshAt] = at }

    @Serializable
    private data class DecoderDto(val codec: String, val hardware: Boolean, val maxFpsByLines: Map<Int, Int>, val hdr: Boolean)

    @Serializable
    private data class ProfileDto(
        val decoders: List<DecoderDto>,
        val hdrDisplay: Boolean,
        val displayLines: Int,
        val throughputKbps: Int?,
        val matchFrameRate: Boolean,
    ) {
        fun toModel() = DeviceProfile(
            decoders = decoders.mapNotNull { d ->
                val codec = runCatching { VideoCodec.valueOf(d.codec) }.getOrNull() ?: return@mapNotNull null
                DecoderCaps(codec, d.hardware, d.maxFpsByLines, d.hdr)
            },
            hdrDisplay = hdrDisplay,
            displayLines = displayLines,
            throughputKbps = throughputKbps,
            matchFrameRate = matchFrameRate,
        )

        companion object {
            fun from(p: DeviceProfile) = ProfileDto(
                decoders = p.decoders.map { DecoderDto(it.codec.name, it.hardware, it.maxFpsByLines, it.hdr) },
                hdrDisplay = p.hdrDisplay,
                displayLines = p.displayLines,
                throughputKbps = p.throughputKbps,
                matchFrameRate = p.matchFrameRate,
            )
        }
    }
}
