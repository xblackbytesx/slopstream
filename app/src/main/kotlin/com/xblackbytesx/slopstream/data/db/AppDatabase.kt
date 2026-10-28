package com.xblackbytesx.slopstream.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/** Schema history lives in app/schemas/; every bump is an AutoMigration checked against it. */
@Database(
    entities = [SubscriptionEntity::class, FeedItemEntity::class, WatchStateEntity::class, WatchLaterEntity::class],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        // 2: subscription.favorite
        AutoMigration(from = 1, to = 2),
        // 3: sync bookkeeping on the synced tables, history fields and a tombstone on watch_state
        AutoMigration(from = 2, to = 3),
        // 4: subscription.feedRev (the server's video lists, decision 19)
        AutoMigration(from = 3, to = 4),
        // 5: feed_item.archived (older videos loaded on a channel page, decision 23)
        AutoMigration(from = 4, to = 5),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptions(): SubscriptionDao
    abstract fun feed(): FeedDao
    abstract fun watch(): WatchDao
    abstract fun sync(): SyncDao
}
