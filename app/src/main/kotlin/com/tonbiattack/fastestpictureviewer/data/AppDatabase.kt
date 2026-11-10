package com.tonbiattack.fastestpictureviewer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * アプリの(Room)データベース。
 * - images:                   MediaStore 走査結果のメタ情報 (単一の真実のソース)
 * - sync_state:               差分同期の時刻管理 (単一行)
 * - volume_sync_state:        ボリューム単位の MediaStore 世代番号管理
 * - favorite_images:          お気に入り情報 (本体画像に変更を加えない)
 */
@Database(
    entities = [
        ImageItem::class,
        SyncStateEntity::class,
        VolumeSyncStateEntity::class,
        FavoriteImageEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageItemDao(): ImageItemDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun volumeSyncStateDao(): VolumeSyncStateDao
    abstract fun favoriteImageDao(): FavoriteImageDao

    companion object {
        const val DATABASE_NAME = "fastest-picture-viewer.db"

        /** 通常の DB を構築する */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            ).addMigrations(MIGRATION_1_2).build()

        /** テスト用のメモリ DB を構築する */
        fun buildInMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        /**
         * v1 → v2: ボリューム同期状態テーブルを追加する。
         * 既存の画像キャッシュはそのまま維持する。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `volume_sync_state` (
                        `volumeName` TEXT NOT NULL,
                        `contentGeneration` INTEGER,
                        `lastScanSec` INTEGER NOT NULL,
                        PRIMARY KEY(`volumeName`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
