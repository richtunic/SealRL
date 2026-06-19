package com.junkfood.seal.database.objects

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class QueueStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELED,
    PAUSED
}

@Entity(tableName = "download_queue")
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val normalizedUrl: String,
    val platform: String,
    val title: String?,
    val progress: Float,
    val status: QueueStatus,
    val errorMessage: String?,
    val outputPath: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
