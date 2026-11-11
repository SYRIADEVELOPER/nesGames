package com.nesgames.app.library

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Cached library entry. Rows are never deleted when a file disappears (only flagged), so favourites and the save key survive. */
@Entity(tableName = "games")
data class GameRow(
    @PrimaryKey val id: String,       // document uri (+ "!" + zip entry)
    val title: String,
    val fileName: String,
    val system: String,
    val uri: String,
    val zipEntry: String?,
    val size: Long,                   // playable content size (disc = sum of tracks)
    val fileSize: Long,               // size of the scanned document, for change detection
    val mtime: Long,
    val discFiles: String,            // '\n'-joined track names for CUE discs
    val dirUri: String?,
    val fingerprint: String,          // sha1(first 64 KiB) + size: recognises a renamed/moved file
    val saveKey: String,              // folder name under files/saves; survives renames
    val favorite: Boolean = false,
    val lastPlayed: Long = 0,
    val missing: Boolean = false,
)

@Dao
interface GameDao {
    @Query("SELECT * FROM games") suspend fun all(): List<GameRow>
    @Query("SELECT * FROM games WHERE missing = 0 ORDER BY title COLLATE NOCASE") fun observe(): Flow<List<GameRow>>
    @Upsert suspend fun upsert(rows: List<GameRow>)
    @Query("DELETE FROM games WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE games SET missing = 1 WHERE id IN (:ids)") suspend fun markMissing(ids: List<String>)
    @Query("UPDATE games SET favorite = :fav WHERE id = :id") suspend fun setFavorite(id: String, fav: Boolean)
    @Query("UPDATE games SET lastPlayed = :t WHERE id = :id") suspend fun setLastPlayed(id: String, t: Long)
}

@Database(entities = [GameRow::class], version = 1, exportSchema = false)
abstract class GameDb : RoomDatabase() {
    abstract fun games(): GameDao
    companion object {
        @Volatile private var inst: GameDb? = null
        fun get(ctx: Context): GameDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, GameDb::class.java, "library.db").build().also { inst = it }
        }
    }
}
