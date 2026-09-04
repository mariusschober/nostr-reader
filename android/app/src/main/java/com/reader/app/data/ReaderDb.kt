package com.reader.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "documents")
data class DocumentEntity(
  @PrimaryKey val documentId: String,
  val title: String,
  val sourceType: String,
  val sourceName: String?,
  val sourceUrl: String?,
  val author: String?,
  val publishedAt: Long?,
  val capturedAt: Long,
  val language: String?,
  val canonicalMarkdown: String,
  val wordCount: Int,
  val parserVersion: Int,
  val state: String, // unread|reading (progress bucket; list membership lives in `list`)
  val list: String = "inbox", // inbox|priority|later|archived
  val progressBlockId: String?,
  val progressCharOffset: Int,
  val progressFraction: Float,
  val lastOpenedAt: Long,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(tableName = "channels")
data class ChannelEntity(
  @PrimaryKey val channelId: String,
  val receiverPubkey: String,
  val trustedSenderPubkey: String,
  val createdAt: Long,
  val revokedAt: Long?,
  val relaysJson: String,
)

@Entity(tableName = "incoming_chunks", primaryKeys = ["transferId", "index"])
data class ChunkEntity(
  val transferId: String,
  val index: Int,
  val documentId: String,
  val count: Int,
  val bytesB64: String,
  val receivedAt: Long,
  val expiresAt: Long,
)

@Dao
interface DocumentDao {
  @Query("SELECT * FROM documents WHERE list = 'inbox' ORDER BY createdAt DESC")
  suspend fun inbox(): List<DocumentEntity>

  @Query("SELECT * FROM documents WHERE list = 'archived' ORDER BY updatedAt DESC")
  suspend fun archived(): List<DocumentEntity>

  @Query("SELECT * FROM documents WHERE list = :list ORDER BY createdAt DESC")
  suspend fun byList(list: String): List<DocumentEntity>

  @Query("UPDATE documents SET list = :list, updatedAt = :now WHERE documentId = :id")
  suspend fun setList(id: String, list: String, now: Long)

  @Query("DELETE FROM documents WHERE documentId = :id")
  suspend fun deleteById(id: String)

  @Query("SELECT * FROM documents WHERE documentId = :id LIMIT 1")
  suspend fun byId(id: String): DocumentEntity?

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(doc: DocumentEntity): Long

  @Update
  suspend fun update(doc: DocumentEntity)

  @Query("UPDATE documents SET state = :state, updatedAt = :now WHERE documentId = :id")
  suspend fun setState(id: String, state: String, now: Long)

  @Query("SELECT COALESCE(SUM(wordCount),0) FROM documents WHERE state IN ('unread','reading')")
  suspend fun unreadWords(): Long

  @Query("SELECT COALESCE(SUM(wordCount),0) FROM documents WHERE list = :list")
  suspend fun wordsInList(list: String): Long
}

@Dao
interface ChannelDao {
  @Query("SELECT * FROM channels WHERE revokedAt IS NULL")
  suspend fun active(): List<ChannelEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(channel: ChannelEntity)

  @Query("UPDATE channels SET revokedAt = :now WHERE channelId = :id")
  suspend fun revoke(id: String, now: Long)
}

@Dao
interface ChunkDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(chunk: ChunkEntity)

  @Query("SELECT * FROM incoming_chunks WHERE transferId = :t ORDER BY `index`")
  suspend fun forTransfer(t: String): List<ChunkEntity>

  @Query("DELETE FROM incoming_chunks WHERE transferId = :t")
  suspend fun clearTransfer(t: String)

  @Query("DELETE FROM incoming_chunks WHERE expiresAt < :now")
  suspend fun purgeExpired(now: Long)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE documents ADD COLUMN list TEXT NOT NULL DEFAULT 'inbox'")
    // `finished` meant read: archive is anything read or discarded.
    db.execSQL("UPDATE documents SET list = 'archived' WHERE state IN ('archived', 'finished')")
  }
}

@Database(entities = [DocumentEntity::class, ChannelEntity::class, ChunkEntity::class], version = 2, exportSchema = false)
abstract class ReaderDb : RoomDatabase() {
  abstract fun documents(): DocumentDao
  abstract fun channels(): ChannelDao
  abstract fun chunks(): ChunkDao

  companion object {
    @Volatile
    private var instance: ReaderDb? = null
    fun get(ctx: Context): ReaderDb = instance ?: synchronized(this) {
      instance ?: Room.databaseBuilder(ctx.applicationContext, ReaderDb::class.java, "reader.db")
        .addMigrations(MIGRATION_1_2)
        .build()
        .also { instance = it }
    }
  }
}
