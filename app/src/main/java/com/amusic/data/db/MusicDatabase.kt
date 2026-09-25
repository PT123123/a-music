package com.amusic.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.amusic.data.model.Favorite
import com.amusic.data.model.Playlist
import com.amusic.data.model.PlaylistSong
import com.amusic.data.model.RecommendMap
import com.amusic.data.model.Song

@Database(
    entities = [Song::class, Playlist::class, PlaylistSong::class, Favorite::class, RecommendMap::class],
    version = 4,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun recommendMapDao(): RecommendMapDao

    companion object {
        /**
         * v2 -> v3 adds the recycle bin. `songs` gets a nullable `trashedAt`; everything else
         * is untouched, so the user's playlists and likes survive the upgrade. Written by hand
         * to match exactly what Room expects for `val trashedAt: Long?`.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `songs` ADD COLUMN `trashedAt` INTEGER")
            }
        }

        /**
         * v3 -> v4 adds the recommendation join table (content-hash track id per song).
         * Purely additive, so playlists/likes/favorites survive untouched.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recommend_map` (" +
                        "`songId` INTEGER NOT NULL PRIMARY KEY, " +
                        "`trackId` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, " +
                        "`dateAdded` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recommend_map_trackId` ON `recommend_map` (`trackId`)")
            }
        }

        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun get(context: Context): MusicDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "amusic.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
