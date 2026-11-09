package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * アプリの(Room)データベース。
 * - images:                   MediaStore 走査結果のメタ情報 (単一の真実のソース)
 * - sync_state:               差分同期の時刻管理 (単一行)
 * - favorite_images:          お気に入り情報 (本体画像に変更を加えない)
 */
@Database(
    entities = [ImageItem::class, SyncStateEntity::class, FavoriteImageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageItemDao(): ImageItemDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun favoriteImageDao(): FavoriteImageDao

    companion object {
        const val DATABASE_NAME = "fastest-picture-viewer.db"

        /** 通常の DB を構築する */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            ).build()

        /** テスト用のメモリ DB を構築する */
        fun buildInMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }
}
