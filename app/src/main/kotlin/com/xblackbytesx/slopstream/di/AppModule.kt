package com.xblackbytesx.slopstream.di

import android.content.Context
import androidx.room.Room
import com.xblackbytesx.slopstream.core.ContentSource
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.Accounts
import com.xblackbytesx.slopstream.data.KeystoreSecretStore
import com.xblackbytesx.slopstream.data.SecretStore
import com.xblackbytesx.slopstream.data.db.AppDatabase
import com.xblackbytesx.slopstream.data.db.FeedDao
import com.xblackbytesx.slopstream.data.db.SubscriptionDao
import com.xblackbytesx.slopstream.data.db.WatchDao
import com.xblackbytesx.slopstream.floatplane.FloatplaneApi
import com.xblackbytesx.slopstream.floatplane.FloatplaneSource
import com.xblackbytesx.slopstream.floatplane.SessionAuthorizer
import com.xblackbytesx.slopstream.potoken.WebPoTokenProvider
import com.xblackbytesx.slopstream.youtube.YoutubeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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

    /** Signed in with the stored website session; without one every call is a SourceAuthException. */
    @Provides
    @Singleton
    fun floatplane(client: OkHttpClient, accounts: Accounts): FloatplaneSource =
        FloatplaneSource(FloatplaneApi(client, SessionAuthorizer { accounts.secret(SourceKey.FLOATPLANE) }))

    @Provides
    @IntoSet
    fun floatplaneAsSource(source: FloatplaneSource): ContentSource = source

    @Provides
    @Singleton
    fun secretStore(store: KeystoreSecretStore): SecretStore = store

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "slopstream.db").build()

    @Provides fun subscriptionDao(db: AppDatabase): SubscriptionDao = db.subscriptions()
    @Provides fun feedDao(db: AppDatabase): FeedDao = db.feed()
    @Provides fun watchDao(db: AppDatabase): WatchDao = db.watch()
}
