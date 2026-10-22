package com.xblackbytesx.slopstream.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/** Schema history lives in app/schemas/; every bump is an AutoMigration checked against it. */
@Database(
    entities = [SubscriptionEntity::class, FeedItemEntity::class, WatchStateEntity::class, WatchLaterEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        // 2: subscription.favorite
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptions(): SubscriptionDao
    abstract fun feed(): FeedDao
    abstract fun watch(): WatchDao
}
