package com.dbexplorer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TitleEntity::class, FacetEntity::class, PlayedEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TitleDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "catalogue.db")
                // Version 1 kept a phone-built catalogue that is no longer used.
                .fallbackToDestructiveMigrationFrom(1)
                .build()
    }
}
