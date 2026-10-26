package com.xblackbytesx.slopstream

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.xblackbytesx.slopstream.data.RefreshWorker
import com.xblackbytesx.slopstream.data.Settings
import com.xblackbytesx.slopstream.data.sync.Durations
import com.xblackbytesx.slopstream.data.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class SlopstreamApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var okHttp: OkHttpClient
    @Inject lateinit var settings: Settings
    @Inject lateinit var sync: SyncScheduler
    @Inject lateinit var durations: Durations

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            settings.settings.map { it.refreshHours }.distinctUntilChanged().collect { hours ->
                RefreshWorker.schedule(this@SlopstreamApp, hours)
            }
        }
        // Does nothing until a sync server is connected (Settings).
        sync.start()
        durations.start(appScope)
    }

    /** Thumbnails through the shared client, 250 MB on disk: a TV grid scrolls fast. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttp })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbs")).maxSizeBytes(250L * 1024 * 1024).build() }
        .crossfade(150)
        .build()
}
