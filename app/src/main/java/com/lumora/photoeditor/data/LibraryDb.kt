package com.lumora.photoeditor.data

import androidx.room.*

@Entity(tableName = "images")
data class LibraryImage(
    @PrimaryKey val id: String,
    val contentUri: String?,
    val displayName: String,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
    val folder: String,
    val rating: Int = 0,
    val favorite: Boolean = false,
    val lastEdited: Long = 0L,
    val editCount: Int = 0,
)

@Entity(
    tableName = "history",
    foreignKeys = [ForeignKey(
        entity = LibraryImage::class,
        parentColumns = ["id"],
        childColumns = ["imageId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("imageId")],
)
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val imageId: String,
    val timestamp: Long,
    val label: String,
    val stateJson: String,
)

@Dao
interface LibraryDao {
    @Upsert
    suspend fun upsertImage(image: LibraryImage)

    @Query("SELECT COUNT(*) FROM images")
    suspend fun imageCount(): Int

    @Query("SELECT * FROM images ORDER BY dateAdded DESC")
    suspend fun allImages(): List<LibraryImage>

    @Query("SELECT * FROM images WHERE favorite = 1 ORDER BY dateAdded DESC")
    suspend fun favorites(): List<LibraryImage>

    @Query("SELECT * FROM images WHERE rating >= :minRating ORDER BY dateAdded DESC")
    suspend fun byRating(minRating: Int): List<LibraryImage>

    @Query("SELECT * FROM images WHERE lastEdited > 0 ORDER BY dateAdded DESC")
    suspend fun edited(): List<LibraryImage>

    @Query("SELECT * FROM images WHERE lastEdited = 0 ORDER BY dateAdded DESC")
    suspend fun unedited(): List<LibraryImage>

    /**
     * Contains-search over name and folder. The [q] parameter MUST be the
     * output of [escapeLikePattern] (applied by the caller), because the
     * pattern is wrapped in `%...%` and the query declares ESCAPE '\':
     * passing a raw user query would turn typed %, _ or \ into wildcards.
     */
    @Query("SELECT * FROM images WHERE displayName LIKE '%' || :q || '%' ESCAPE '\\' OR folder LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY dateAdded DESC")
    suspend fun search(q: String): List<LibraryImage>

    @Query("SELECT * FROM images WHERE id = :id")
    suspend fun byId(id: String): LibraryImage?

    @Query("UPDATE images SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: String, rating: Int)

    @Query("UPDATE images SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: String, fav: Boolean)

    @Query("UPDATE images SET lastEdited = :ts, editCount = editCount + 1 WHERE id = :id")
    suspend fun touchEdited(id: String, ts: Long)

    @Query("DELETE FROM images WHERE id = :id")
    suspend fun deleteImage(id: String)

    @Insert
    suspend fun addHistory(entry: HistoryEntry)

    @Query("SELECT * FROM history WHERE imageId = :imageId ORDER BY timestamp DESC LIMIT 50")
    suspend fun history(imageId: String): List<HistoryEntry>

    @Query("DELETE FROM history WHERE imageId = :imageId")
    suspend fun clearHistory(imageId: String)
}

@Database(entities = [LibraryImage::class, HistoryEntry::class], version = 1, exportSchema = false)
abstract class LibraryDb : RoomDatabase() {
    abstract fun library(): LibraryDao
}
