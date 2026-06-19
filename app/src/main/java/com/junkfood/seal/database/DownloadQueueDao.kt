package com.junkfood.seal.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.junkfood.seal.database.objects.QueueItemEntity
import com.junkfood.seal.database.objects.QueueStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadQueueDao {

    @Query("SELECT * FROM download_queue ORDER BY createdAt ASC")
    fun observeQueue(): Flow<List<QueueItemEntity>>

    @Query("SELECT * FROM download_queue WHERE status = :status ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextByStatus(status: QueueStatus): QueueItemEntity?

    @Query("SELECT * FROM download_queue WHERE id = :id")
    suspend fun getById(id: Long): QueueItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: QueueItemEntity): Long

    @Update
    suspend fun update(item: QueueItemEntity)

    @Query("DELETE FROM download_queue WHERE status = :status")
    suspend fun deleteByStatus(status: QueueStatus)

    @Query("DELETE FROM download_queue")
    suspend fun clearQueue()

    @Query("DELETE FROM download_queue WHERE id = :id")
    suspend fun deleteById(id: Long)
}
