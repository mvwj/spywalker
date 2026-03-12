package com.spywalker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WalkSession::class, 
        WalkPoint::class,
        ExploredCell::class,
        CityBounds::class,
        // OSM entities
        City::class,
        RoadSegment::class,
        RoadPoint::class,
        RoadCoverageChunk::class
    ],
    version = 5,
    exportSchema = false
)
abstract class WalkDatabase : RoomDatabase() {
    abstract fun walkDao(): WalkDao
    abstract fun cityDao(): CityDao
    
    companion object {
        @Volatile
        private var INSTANCE: WalkDatabase? = null
        
        fun getDatabase(context: Context): WalkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WalkDatabase::class.java,
                    "walk_tracker_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

