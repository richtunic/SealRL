package com.junkfood.seal.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.database.objects.QueueItemEntity

@Database(
    entities = [QueueItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BulkDatabase : RoomDatabase() {
    abstract fun downloadQueueDao(): DownloadQueueDao
}

object BulkDatabaseUtil {
    private const val DATABASE_NAME = "bulk_database"
    val db: BulkDatabase by lazy {
        Room.databaseBuilder(context, BulkDatabase::class.java, DATABASE_NAME).build()
    }
    val queueDao: DownloadQueueDao by lazy {
        db.downloadQueueDao()
    }
}
