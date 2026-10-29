package com.xblackbytesx.slopstream.di

import android.content.Context
import androidx.room.Room
import com.xblackbytesx.slopstream.core.ContentSource
import com.xblackbytesx.slopstream.data.Accounts
import com.xblackbytesx.slopstream.data.FloatplaneSites
import com.xblackbytesx.slopstream.data.KeystoreSecretStore
import com.xblackbytesx.slopstream.data.SecretStore
import com.xblackbytesx.slopstream.data.TokenStore
import com.xblackbytesx.slopstream.data.db.AppDatabase
import com.xblackbytesx.slopstream.data.download.AudioExtractor
import com.xblackbytesx.slopstream.data.download.DownloadScheduler
import com.xblackbytesx.slopstream.data.download.DownloadWorker
import com.xblackbytesx.slopstream.data.download.TransformerAudioExtractor
import com.xblackbytesx.slopstream.data.db.DownloadDao
import com.xblackbytesx.slopstream.data.db.FeedDao
import com.xblackbytesx.slopstream.data.db.SubscriptionDao
import com.xblackbytesx.slopstream.data.db.WatchDao
import com.xblackbytesx.slopstream.data.sync.DataStoreSyncStore
import com.xblackbytesx.slopstream.data.sync.OkHttpSyncApiFactory
import com.xblackbytesx.slopstream.data.sync.SyncApiFactory
import com.xblackbytesx.slopstream.data.sync.SyncStore
import com.xblackbytesx.slopstream.floatplane.FloatplaneApi
import com.xblackbytesx.slopstream.floatplane.FloatplaneSite
import com.xblackbytesx.slopstream.floatplane.FloatplaneSource
import com.xblackbytesx.slopstream.floatplane.SessionAuthorizer
import com.xblackbytesx.slopstream.potoken.WebPoTokenProvider
import com.xblackbytesx.slopstream.youtube.YoutubeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** One client for extraction, feeds, thumbnails and media: shared pool, shared DNS cache. */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .build()

    @Provides
    @Singleton
    @IntoSet
    fun youtube(@ApplicationContext context: Context, client: OkHttpClient): ContentSource =
        YoutubeSource(client, Locale.getDefault(), WebPoTokenProvider(context, client))

    /**
     * Floatplane and Sauce+, one platform: each signed in with its stored website session;
     * without one every call is a SourceAuthException.
     */
    @Provides
    @Singleton
    fun floatplaneSites(client: OkHttpClient, accounts: Accounts): FloatplaneSites = FloatplaneSites(
        FloatplaneSite.ALL.map { site ->
            FloatplaneSource(FloatplaneApi(client, SessionAuthorizer(site.sessionCookie) { accounts.secret(site.key) }, site.baseUrl), site)
        },
    )

    @Provides
    @ElementsIntoSet
    fun floatplaneSources(sites: FloatplaneSites): Set<ContentSource> = sites.all.toSet()

    @Provides
    @Singleton
    fun secretStore(store: KeystoreSecretStore): SecretStore = store

    @Provides
    @Singleton
    fun tokenStore(store: KeystoreSecretStore): TokenStore = store

    @Provides
    @Singleton
    fun syncStore(store: DataStoreSyncStore): SyncStore = store

    @Provides
    fun syncApis(factory: OkHttpSyncApiFactory): SyncApiFactory = factory

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "slopstream.db").build()

    @Provides fun subscriptionDao(db: AppDatabase): SubscriptionDao = db.subscriptions()
    @Provides fun feedDao(db: AppDatabase): FeedDao = db.feed()
    @Provides fun watchDao(db: AppDatabase): WatchDao = db.watch()
    @Provides fun downloadDao(db: AppDatabase): DownloadDao = db.downloads()

    @Provides
    fun audioExtractor(extractor: TransformerAudioExtractor): AudioExtractor = extractor

    @Provides
    fun downloadScheduler(@ApplicationContext context: Context): DownloadScheduler = DownloadScheduler { DownloadWorker.enqueue(context) }
}
