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
import com.amusic.data.model.Song

@Database(
    entities = [Song::class, Playlist::class, PlaylistSong::class, Favorite::class],
    version = 3,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao

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

        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun get(context: Context): MusicDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "amusic.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
