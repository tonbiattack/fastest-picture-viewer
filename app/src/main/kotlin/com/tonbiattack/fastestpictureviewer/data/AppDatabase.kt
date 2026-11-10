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
 * - favorite_images:          画像単位のお気に入り (本体画像に変更を加えない)
 * - favorite_folders:         フォルダ単位のお気に入り
 * - excluded_folders:         フォルダ一覧から除外するフォルダ (非破壊)
 * - folder_scroll_positions:  フォルダ別の画像一覧スクロール位置
 * - recent_folders:           最近閲覧したフォルダ
 */
@Database(
    entities = [
        ImageItem::class,
        SyncStateEntity::class,
        VolumeSyncStateEntity::class,
        FavoriteImageEntity::class,
        FavoriteFolderEntity::class,
        ExcludedFolderEntity::class,
        FolderScrollPositionEntity::class,
        RecentFolderEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageItemDao(): ImageItemDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun volumeSyncStateDao(): VolumeSyncStateDao
    abstract fun favoriteImageDao(): FavoriteImageDao
    abstract fun favoriteFolderDao(): FavoriteFolderDao
    abstract fun excludedFolderDao(): ExcludedFolderDao
    abstract fun folderScrollPositionDao(): FolderScrollPositionDao
    abstract fun recentFolderDao(): RecentFolderDao

    companion object {
        const val DATABASE_NAME = "fastest-picture-viewer.db"

        /** 通常の DB を構築する */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6
            ).build()

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

        /** v2 → v3: 除外フォルダテーブルを追加する。既存キャッシュは維持する。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `excluded_folders` (
                        `folderKey` TEXT NOT NULL,
                        `volumeName` TEXT NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `excludedAtSec` INTEGER NOT NULL,
                        PRIMARY KEY(`folderKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v3 → v4: フォルダお気に入りテーブルを追加する。既存キャッシュは維持する。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorite_folders` (
                        `folderKey` TEXT NOT NULL,
                        `volumeName` TEXT NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `addedAtSec` INTEGER NOT NULL,
                        PRIMARY KEY(`folderKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v4 → v5: 自然順ソートキー列とインデックスを追加する。
         * 既存行のキーは空のまま移行し、フォルダを開いたときに遅延バックフィルする
         * (35万件の一括更新で起動を長時間ブロックしないため)。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `images` ADD COLUMN `naturalSortKey` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_images_folderKey_sortName_mediaId`")
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_images_folderKey_naturalSortKey_sortName_mediaId`
                    ON `images` (`folderKey`, `naturalSortKey`, `sortName`, `mediaId`)
                    """.trimIndent()
                )
            }
        }

        /** v5 → v6: フォルダ別スクロール位置と最近閲覧フォルダを追加する。 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `folder_scroll_positions` (
                        `folderKey` TEXT NOT NULL,
                        `anchorItemId` TEXT,
                        `anchorIndex` INTEGER NOT NULL,
                        `anchorOffsetPx` INTEGER NOT NULL,
                        `updatedAtSec` INTEGER NOT NULL,
                        PRIMARY KEY(`folderKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recent_folders` (
                        `folderKey` TEXT NOT NULL,
                        `openedAtSec` INTEGER NOT NULL,
                        PRIMARY KEY(`folderKey`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
