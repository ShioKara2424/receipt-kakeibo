package com.shiokara.receiptkakeibo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 見つけたレシート。向きを直した画像をアプリ内に保存し、送信したかどうかを覚えておく */
@Entity(tableName = "receipts")
data class DetectedReceipt(
    @PrimaryKey val mediaId: Long,
    /** 向きを直して縮小した JPEG のパス (アプリ内) */
    val imagePath: String,
    /** 撮影日時 (エポックミリ秒) */
    val takenAt: Long,
    val detectedAt: Long = System.currentTimeMillis(),
    /** Claude に送る操作をした日時。未送信なら null */
    val sentAt: Long? = null,
)

/** 一度調べた写真 (レシートでなかったものも含む)。同じ写真を何度も調べないために使う */
@Entity(tableName = "processed_images")
data class ProcessedImage(
    @PrimaryKey val mediaId: Long,
    val isReceipt: Boolean,
    val processedAt: Long = System.currentTimeMillis(),
)

@Dao
interface ReceiptDao {
    @Query("SELECT * FROM receipts ORDER BY takenAt DESC")
    fun observeAll(): Flow<List<DetectedReceipt>>

    @Query("SELECT * FROM receipts WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): DetectedReceipt?

    @Query("SELECT * FROM receipts WHERE detectedAt < :before")
    suspend fun olderThan(before: Long): List<DetectedReceipt>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(receipt: DetectedReceipt)

    @Query("UPDATE receipts SET sentAt = :sentAt WHERE mediaId = :mediaId")
    suspend fun markSent(mediaId: Long, sentAt: Long)

    @Query("DELETE FROM receipts WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM processed_images WHERE mediaId = :mediaId)")
    suspend fun isProcessed(mediaId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM processed_images WHERE mediaId = :mediaId AND isReceipt = 1)")
    suspend fun isKnownReceipt(mediaId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markProcessed(image: ProcessedImage)
}

@Database(entities = [DetectedReceipt::class, ProcessedImage::class], version = 1, exportSchema = true)
abstract class ReceiptDatabase : RoomDatabase() {
    abstract fun dao(): ReceiptDao

    companion object {
        @Volatile private var instance: ReceiptDatabase? = null

        fun get(context: Context): ReceiptDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ReceiptDatabase::class.java, "receipts.db")
                .build().also { instance = it }
        }
    }
}
